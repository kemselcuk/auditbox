package io.auditbox.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Stage 2 integration test (plan task T2.1, adapted for the M11.1 partitioning, decision
 * D21): applies the plain-SQL Flyway migrations to a real PostgreSQL (Testcontainers, same
 * pinned image as docker-compose.yml) on a clean database and asserts the resulting
 * partitioned schema. Plain Flyway + JDBC — no Spring context, no JPA entity.
 */
class EventsSchemaMigrationTest {

	/** Pinned PostgreSQL image; keep in sync with docker-compose.yml. */
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
			.withDatabaseName("auditbox");

	/** SQLSTATE of a unique violation — the race signal the write path catches (D4/D21). */
	private static final String UNIQUE_VIOLATION = "23505";

	/** Matches bounds like FOR VALUES FROM ('2026-10-01 00:00:00+00', '2026-11-01 00:00:00+00'). */
	private static final Pattern BOUND_PATTERN = Pattern
			.compile("FOR VALUES FROM \\('([^']+)'\\) TO \\('([^']+)'\\)");

	@BeforeAll
	static void startContainerAndApplyMigration() {
		postgres.start();
		Flyway.configure()
				.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
				.load()
				.migrate();
	}

	@AfterAll
	static void stopContainer() {
		postgres.stop();
	}

	private record Column(String name, String type, boolean nullable, boolean identity, String defaultValue) {
	}

	private record Partition(String name, String bound) {
	}

	/** The managed monthly window the V2 migration builds: current month - 25 .. + 3. */
	private static List<YearMonth> expectedMonthlyWindow() {
		YearMonth current = YearMonth.now(ZoneOffset.UTC);
		List<YearMonth> window = new ArrayList<>();
		for (int offset = -25; offset <= 3; offset++) {
			window.add(current.plusMonths(offset));
		}
		return window;
	}

	private static String monthlyPartitionName(YearMonth month) {
		// Same naming as the V2 DO block: to_char(..., 'YYYY_MM').
		return "events_" + "%04d_%02d".formatted(month.getYear(), month.getMonthValue());
	}

	private static Connection connection() throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

	@Test
	void flywayAppliesV1AndV2ToACleanDatabase() throws SQLException {
		try (Connection connection = connection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery(
						"SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank")) {
			assertThat(result.next()).isTrue();
			assertThat(result.getString("version")).isEqualTo("1");
			assertThat(result.getString("description")).isEqualTo("create events");
			assertThat(result.getBoolean("success")).isTrue();
			assertThat(result.next()).isTrue();
			assertThat(result.getString("version")).isEqualTo("2");
			assertThat(result.getString("description")).isEqualTo("partition events");
			assertThat(result.getBoolean("success")).isTrue();
			assertThat(result.next()).isFalse();
		}
	}

	@Test
	void eventsTableIsRangePartitionedOnOccurredAt() throws SQLException {
		try (Connection connection = connection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT c.relkind, pt.partstrat, pg_get_partkeydef(c.oid)
						FROM pg_class c
						JOIN pg_partitioned_table pt ON pt.partrelid = c.oid
						WHERE c.relnamespace = 'public'::regnamespace AND c.relname = 'events'""")) {
			assertThat(result.next()).isTrue();
			// relkind 'p' = partitioned table: the parent is no longer a plain table (D21).
			assertThat(result.getString("relkind")).isEqualTo("p");
			// 'r' = RANGE strategy, keyed on the UTC-normalized occurred_at column (D10).
			assertThat(result.getString("partstrat")).isEqualTo("r");
			assertThat(result.getString(3)).isEqualTo("RANGE (occurred_at)");
			assertThat(result.next()).isFalse();
		}
	}

	@Test
	void eventsTableHasTheExpectedColumnsAndTypes() throws SQLException {
		List<Column> columns;
		try (Connection connection = connection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT column_name, data_type, is_nullable, is_identity, column_default
						FROM information_schema.columns
						WHERE table_schema = 'public' AND table_name = 'events'
						ORDER BY ordinal_position""")) {
			columns = readColumns(result);
		}

		assertThat(columns).extracting(Column::name, Column::type).containsExactly(
				tuple("id", "bigint"),
				tuple("event_id", "text"),
				tuple("user_id", "text"),
				tuple("event_type", "text"),
				tuple("source", "text"),
				tuple("occurred_at", "timestamp with time zone"),
				tuple("received_at", "timestamp with time zone"),
				tuple("metadata", "jsonb"),
				tuple("content_hash", "text"),
				tuple("created_at", "timestamp with time zone"));

		// Identity column, everything NOT NULL except metadata.
		assertThat(columns.get(0).name()).isEqualTo("id");
		assertThat(columns.get(0).identity()).isTrue();
		assertThat(columns).filteredOn(column -> !column.name().equals("metadata"))
				.allSatisfy(column -> assertThat(column.nullable()).isFalse());
		assertThat(columns).filteredOn(column -> column.name().equals("metadata"))
				.singleElement()
				.satisfies(column -> assertThat(column.nullable()).isTrue());

		// Server-side timestamps default to now(); content columns have no default.
		assertThat(columns).filteredOn(column -> column.name().equals("received_at")
				|| column.name().equals("created_at"))
				.allSatisfy(column -> assertThat(column.defaultValue()).isEqualTo("now()"));
		assertThat(columns).filteredOn(column -> !column.name().equals("received_at")
				&& !column.name().equals("created_at") && !column.name().equals("id"))
				.allSatisfy(column -> assertThat(column.defaultValue()).isNull());
	}

	@Test
	void primaryKeyIncludesThePartitionKey() throws SQLException {
		List<String> pkColumns = new ArrayList<>();
		try (Connection connection = connection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT kcu.column_name
						FROM information_schema.table_constraints tc
						JOIN information_schema.key_column_usage kcu
						  ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
						WHERE tc.table_schema = 'public' AND tc.table_name = 'events'
						  AND tc.constraint_type = 'PRIMARY KEY'
						ORDER BY kcu.ordinal_position""")) {
			while (result.next()) {
				pkColumns.add(result.getString(1));
			}
		}
		// A partitioned primary key must contain the partition key occurred_at (D21).
		assertThat(pkColumns).containsExactly("occurred_at", "id");
	}

	/**
	 * The V1 table constraint on (event_id) is replaced by a unique index that must include
	 * the partition key (D21): duplicates of (occurredAt, eventId) are rejected with 23505,
	 * while the same eventId at a different occurredAt is a DB-level duplicate only of that
	 * pair — the application restores global eventId uniqueness with the advisory lock (D4).
	 */
	@Test
	void uniqueEventIdIndexRejectsDuplicatesOfOccurredAtTimesEventId() throws SQLException {
		String insert = """
				INSERT INTO events (event_id, user_id, event_type, source, occurred_at, content_hash)
				VALUES (?, ?, ?, ?, ?, ?)""";
		try (Connection connection = connection()) {
			// Same eventId, same occurred_at: the unique (occurred_at, event_id) index rejects.
			insertRow(connection, insert, "uq-partition-check", "user-1", "payment.settled", "core-banking",
					"2026-10-05T12:00:00+00", "hash-a");
			try {
				insertRow(connection, insert, "uq-partition-check", "user-1", "payment.settled", "core-banking",
						"2026-10-05T12:00:00+00", "hash-b");
				throw new AssertionError("duplicate (occurred_at, event_id) must be rejected");
			}
			catch (SQLException expected) {
				assertThat(expected.getSQLState()).isEqualTo(UNIQUE_VIOLATION);
			}

			// Same eventId, DIFFERENT occurred_at: accepted by the database itself — this is
			// exactly the case the advisory lock in EventService must prevent (D21); the two
			// rows live in different partitions, so no DB constraint can see them together.
			insertRow(connection, insert, "uq-partition-check", "user-1", "payment.settled", "core-banking",
					"2026-11-05T12:00:00+00", "hash-c");
			Integer rows = queryInt(connection, "SELECT count(*) FROM events WHERE event_id = 'uq-partition-check'");
			assertThat(rows).isEqualTo(2);

			cleanup(connection, "DELETE FROM events WHERE event_id = 'uq-partition-check'");
		}
	}

	@Test
	void keysetEventIdAndFilterIndexesExist() throws SQLException {
		Map<String, String> indexes;
		try (Connection connection = connection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery(
						"SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'events'")) {
			indexes = new HashMap<>();
			while (result.next()) {
				indexes.put(result.getString("indexname"), result.getString("indexdef"));
			}
		}

		// Primary key and kept unique-index name, keyset index for the D3 order, the NEW
		// non-unique event_id index (GET by id + idempotency lookup over every partition,
		// D21) and the three Stage 4 filter indexes.
		assertThat(indexes).containsKeys("ix_events_occurred_at_event_id", "events_pkey", "uq_events_event_id",
				"ix_events_event_id", "ix_events_user_id", "ix_events_event_type", "ix_events_source");
		assertThat(indexes.get("ix_events_occurred_at_event_id")).contains("(occurred_at DESC, event_id DESC)");
		assertThat(indexes.get("uq_events_event_id")).contains("UNIQUE").contains("(occurred_at, event_id)");
		assertThat(indexes.get("ix_events_event_id")).doesNotContain("UNIQUE").contains("(event_id)");
	}

	/**
	 * The DO block of V2 created the initial window (current month - 25 .. + 3) and the
	 * DEFAULT partition; a DEFAULT partition has no range bound expression.
	 */
	@Test
	void initialMonthlyWindowAndDefaultPartitionExistWithTheRightBounds() throws SQLException {
		Map<String, Partition> partitions = partitionsOf(connection());

		Set<String> names = new LinkedHashSet<>();
		for (YearMonth month : expectedMonthlyWindow()) {
			names.add(monthlyPartitionName(month));
		}
		names.add("events_default");
		assertThat(partitions.keySet()).isEqualTo(names);

		// Sample the window edges and the current month: half-open [month start UTC, next
		// month start UTC), pinned to UTC exactly like the app normalizes occurredAt (D10).
		List<YearMonth> sampled = List.of(expectedMonthlyWindow().getFirst(),
				YearMonth.now(ZoneOffset.UTC), expectedMonthlyWindow().getLast());
		for (YearMonth month : sampled) {
			Matcher matcher = BOUND_PATTERN.matcher(partitions.get(monthlyPartitionName(month)).bound());
			assertThat(matcher.matches()).as("bound expression of " + month).isTrue();
			assertThat(parseTimestampLiteral(matcher.group(1)))
					.isEqualTo(month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
			assertThat(parseTimestampLiteral(matcher.group(2)))
					.isEqualTo(month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
		}

		// The DEFAULT partition's bound is exactly "DEFAULT" (it catches everything else).
		assertThat(partitions.get("events_default").bound()).isEqualTo("DEFAULT");
	}

	/** Rows route by occurred_at: managed months → their partition, anything else → default (D21). */
	@Test
	void rowsRouteBetweenMonthlyAndDefaultPartitions() throws SQLException {
		YearMonth current = YearMonth.now(ZoneOffset.UTC);
		String insert = """
				INSERT INTO events (event_id, user_id, event_type, source, occurred_at, content_hash)
				VALUES (?, ?, ?, ?, ?, ?)""";
		try (Connection connection = connection()) {
			// In the managed window: current month and the oldest managed month.
			insertRow(connection, insert, "routing-current", "user-1", "payment.settled", "core-banking",
					current.atDay(15).atTime(12, 0).atOffset(ZoneOffset.UTC).toString(), "hash-1");
			insertRow(connection, insert, "routing-oldest", "user-1", "payment.settled", "core-banking",
					expectedMonthlyWindow().getFirst().atDay(15).atTime(12, 0)
							.atOffset(ZoneOffset.UTC).toString(), "hash-2");
			// Outside the managed window but inside the documented API range (D13):
			// years before the oldest monthly partition and far in the future.
			insertRow(connection, insert, "routing-past", "user-1", "payment.settled", "core-banking",
					"2020-01-15T12:00:00+00", "hash-3");
			insertRow(connection, insert, "routing-future", "user-1", "payment.settled", "core-banking",
					"9999-12-31T23:59:59.999999+00", "hash-4");

			assertThat(partitionOf(connection, "routing-current"))
					.isEqualTo(monthlyPartitionName(current));
			assertThat(partitionOf(connection, "routing-oldest"))
					.isEqualTo(monthlyPartitionName(expectedMonthlyWindow().getFirst()));
			assertThat(partitionOf(connection, "routing-past")).isEqualTo("events_default");
			assertThat(partitionOf(connection, "routing-future")).isEqualTo("events_default");

			cleanup(connection, "DELETE FROM events WHERE event_id LIKE 'routing-%'");
		}
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	/** Children of the events parent: monthly partitions with their bound expression,
	 * plus the DEFAULT partition (bound expression null). */
	private static Map<String, Partition> partitionsOf(Connection connection) throws SQLException {
		Map<String, Partition> partitions = new HashMap<>();
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT c.relname, pg_get_expr(c.relpartbound, c.oid) AS bound
						FROM pg_inherits i
						JOIN pg_class c ON c.oid = i.inhrelid
						WHERE i.inhparent = 'public.events'::regclass""")) {
			while (result.next()) {
				partitions.put(result.getString("relname"), new Partition(result.getString("relname"),
						result.getString("bound")));
			}
		}
		return partitions;
	}

	private static String partitionOf(Connection connection, String eventId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT tableoid::regclass::text FROM events WHERE event_id = ?")) {
			statement.setString(1, eventId);
			try (ResultSet result = statement.executeQuery()) {
				result.next();
				return result.getString(1);
			}
		}
	}

	private static void insertRow(Connection connection, String insert, String eventId, String userId,
			String eventType, String source, String occurredAt, String contentHash) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(insert)) {
			statement.setString(1, eventId);
			statement.setString(2, userId);
			statement.setString(3, eventType);
			statement.setString(4, source);
			statement.setObject(5, OffsetDateTime.parse(occurredAt));
			statement.setString(6, contentHash);
			statement.executeUpdate();
		}
	}

	private static Integer queryInt(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery(sql)) {
			result.next();
			return result.getInt(1);
		}
	}

	private static void cleanup(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	/** Parses a PostgreSQL timestamp literal like '2026-10-01 00:00:00+00' to its instant. */
	private static java.time.Instant parseTimestampLiteral(String literal) {
		String iso = literal.replace(' ', 'T');
		// Normalize a colon-less UTC offset to canonical form.
		if (iso.matches(".*[+-]\\d{2}$")) {
			iso = iso + ":00";
		}
		return OffsetDateTime.parse(iso).toInstant();
	}

	private static List<Column> readColumns(ResultSet result) throws SQLException {
		List<Column> columns = new ArrayList<>();
		while (result.next()) {
			columns.add(new Column(result.getString("column_name"), result.getString("data_type"),
					result.getString("is_nullable").equals("YES"), result.getString("is_identity").equals("YES"),
					result.getString("column_default")));
		}
		return columns;
	}

}

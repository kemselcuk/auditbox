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
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Review F7: the automated V1-with-data migration. Applies ONLY V1 to a DEDICATED
 * database of the same Testcontainers container (separate from
 * {@code EventsSchemaMigrationTest}'s clean-database run, so the two are independent),
 * inserts rows inside and outside the V2 initial window (metadata, explicit
 * received_at/created_at, explicit ids), then applies V2 and asserts:
 *
 * <ul>
 *   <li>every row survived verbatim — ids, occurred_at, received_at, created_at and the
 *       jsonb metadata,</li>
 *   <li>rows routed to the right monthly partition (inside the initial window) or the
 *       DEFAULT partition (outside it),</li>
 *   <li>the partitioned PK (occurred_at, id) and the unique (occurred_at, event_id)
 *       index exist,</li>
 *   <li>the identity sequence's next value is max(id)+1 (V2's setval after the explicit
 *       id copy).</li>
 * </ul>
 *
 * <p>Plain Flyway + JDBC — no Spring context, like {@code EventsSchemaMigrationTest}.
 */
class EventsV1WithDataMigrationTest {

	/** Pinned PostgreSQL image; keep in sync with docker-compose.yml. */
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
			.withDatabaseName("auditbox");

	/**
	 * The dedicated database of this test. A separate SCHEMA would not do: V2 addresses
	 * {@code public.events} by regclass, so the migration pair is only schema-complete
	 * in a database's public schema.
	 */
	private static final String DATABASE = "auditbox_v1_with_data";

	/** The explicit ids of the seeded V1 rows; max(id)+1 must be the sequence's next. */
	private static final int MAX_ID = 44;

	/** The full pre-V2 row snapshot, read back right after the inserts. */
	private static final List<Row> BEFORE = new ArrayList<>();

	/** One full row, compared verbatim across the migration. */
	private record Row(int id, String eventId, String userId, String eventType, String source,
			OffsetDateTime occurredAt, OffsetDateTime receivedAt, String metadataAsText, String contentHash,
			OffsetDateTime createdAt) {
	}

	private static final String SELECT_ROWS = """
			SELECT id, event_id, user_id, event_type, source, occurred_at, received_at,
			       metadata::text AS metadata, content_hash, created_at
			FROM events ORDER BY id""";

	@BeforeAll
	static void startContainerMigrateV1SeedRowsAndMigrateV2() throws SQLException {
		postgres.start();
		try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
			statement.execute("CREATE DATABASE " + DATABASE);
		}
		// 1. ONLY V1, into the dedicated database (target=1 leaves V2 pending).
		Flyway.configure()
				.dataSource(jdbcUrl(), postgres.getUsername(), postgres.getPassword())
				.target("1")
				.load()
				.migrate();
		seedV1Rows();
		try (Connection connection = connection();
				ResultSet result = connection.createStatement().executeQuery(SELECT_ROWS)) {
			while (result.next()) {
				BEFORE.add(readRow(result));
			}
		}
		// 2. The V1 history holds ONLY version 1 ...
		assertThat(historyVersions()).containsExactly("1");
		// ... and then V2 runs over the data.
		Flyway.configure()
				.dataSource(jdbcUrl(), postgres.getUsername(), postgres.getPassword())
				.load()
				.migrate();
	}

	@AfterAll
	static void stopContainer() {
		postgres.stop();
	}

	// ---------------------------------------------------------------------
	// The assertions
	// ---------------------------------------------------------------------

	@Test
	void theMigrationHistoryShowsV1ThenV2BothSuccessful() throws SQLException {
		assertThat(historyVersionsWithSuccess())
				.containsExactly(tuple("1", true), tuple("2", true));
	}

	/** Every column of every row identical after the partitioning copy — verbatim. */
	@Test
	void allRowsSurviveThePartitioningVerbatim() throws SQLException {
		List<Row> after = new ArrayList<>();
		try (Connection connection = connection();
				ResultSet result = connection.createStatement().executeQuery(SELECT_ROWS)) {
			while (result.next()) {
				after.add(readRow(result));
			}
		}
		assertThat(after).hasSameSizeAs(BEFORE);
		for (int i = 0; i < BEFORE.size(); i++) {
			Row before = BEFORE.get(i);
			Row row = after.get(i);
			assertThat(row.id()).as("id of row " + i).isEqualTo(before.id());
			assertThat(row.eventId()).as("eventId of row " + i).isEqualTo(before.eventId());
			assertThat(row.userId()).as("userId of row " + i).isEqualTo(before.userId());
			assertThat(row.eventType()).as("eventType of row " + i).isEqualTo(before.eventType());
			assertThat(row.source()).as("source of row " + i).isEqualTo(before.source());
			assertThat(row.occurredAt().toInstant()).as("occurredAt of row " + i)
					.isEqualTo(before.occurredAt().toInstant());
			assertThat(row.receivedAt().toInstant()).as("receivedAt of row " + i)
					.isEqualTo(before.receivedAt().toInstant());
			assertThat(row.metadataAsText()).as("metadata of row " + i).isEqualTo(before.metadataAsText());
			assertThat(row.contentHash()).as("contentHash of row " + i).isEqualTo(before.contentHash());
			assertThat(row.createdAt().toInstant()).as("createdAt of row " + i)
					.isEqualTo(before.createdAt().toInstant());
		}
	}

	/** In-window rows land on their monthly partition, out-of-window rows on the default. */
	@Test
	void rowsRouteToTheRightMonthlyOrDefaultPartitions() throws SQLException {
		YearMonth current = YearMonth.now(ZoneOffset.UTC);
		try (Connection connection = connection()) {
			assertThat(partitionOf(connection, "v1-in-window-current"))
					.isEqualTo(monthlyPartitionName(current));
			assertThat(partitionOf(connection, "v1-in-window-oldest"))
					.isEqualTo(monthlyPartitionName(current.minusMonths(25)));
			assertThat(partitionOf(connection, "v1-out-window-past")).isEqualTo("events_default");
			assertThat(partitionOf(connection, "v1-out-window-future")).isEqualTo("events_default");
		}
	}

	/** The partitioned PK must include the partition key, and the unique index exists. */
	@Test
	void thePartitionedPrimaryKeyAndTheUniqueIndexExist() throws SQLException {
		List<String> pkColumns = new ArrayList<>();
		try (Connection connection = connection();
				ResultSet result = connection.createStatement().executeQuery("""
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
		assertThat(pkColumns).containsExactly("occurred_at", "id");

		try (Connection connection = connection();
				ResultSet result = connection.createStatement().executeQuery(
						"SELECT indexdef FROM pg_indexes WHERE schemaname = 'public'"
								+ " AND tablename = 'events' AND indexname = 'uq_events_event_id'")) {
			assertThat(result.next()).isTrue();
			assertThat(result.getString(1)).contains("UNIQUE").contains("(occurred_at, event_id)");
		}
	}

	/** V2's setval: the identity's next value continues at max(id)+1 (max = 44). */
	@Test
	void theIdentitySequenceContinuesAtMaxIdPlusOne() throws SQLException {
		try (Connection connection = connection();
				ResultSet result = connection.createStatement().executeQuery(
						"SELECT nextval(pg_get_serial_sequence('events', 'id'))")) {
			assertThat(result.next()).isTrue();
			assertThat(result.getLong(1)).isEqualTo(MAX_ID + 1);
		}
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	private static String jdbcUrl() {
		return postgres.getJdbcUrl().replaceFirst("/[^/]+$", "/" + DATABASE);
	}

	private static Connection adminConnection() throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

	private static Connection connection() throws SQLException {
		return DriverManager.getConnection(jdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

	/**
	 * Seeds four V1 rows with explicit ids (the identity is GENERATED BY DEFAULT),
	 * metadata and explicit received_at/created_at: two inside the V2 initial window
	 * (current month and current − 25) and two outside it (years before it, the far
	 * future edge of the documented API range, D13) so both DEFAULT-partition routes
	 * are covered.
	 */
	private static void seedV1Rows() throws SQLException {
		YearMonth current = YearMonth.now(ZoneOffset.UTC);
		String insert = """
				INSERT INTO events (id, event_id, user_id, event_type, source, occurred_at,
				                    received_at, metadata, content_hash, created_at)
				VALUES (?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?::jsonb, ?, ?::timestamptz)""";
		try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(insert)) {
			insertRow(statement, 41, "v1-in-window-current", "user-v1", "payment.settled", "core-banking",
					current.atDay(15).atTime(12, 0).atOffset(ZoneOffset.UTC) + "",
					"2026-01-01 10:15:30.123456+02", "{\"a\":1,\"nested\":{\"z\":true,\"y\":\"s\"}}", "hash-41",
					"2026-01-01 10:15:31+02");
			insertRow(statement, 42, "v1-in-window-oldest", "user-v1", "payment.settled", "core-banking",
					current.minusMonths(25).atDay(28).atTime(6, 30).atOffset(ZoneOffset.UTC) + "",
					"2026-01-01 10:15:30.123456+02", null, "hash-42", "2026-01-01 10:15:31+02");
			insertRow(statement, 43, "v1-out-window-past", "user-v1", "payment.settled", "core-banking",
					"2020-01-15T12:00:00+00", "2026-01-01 10:15:30.123456+02", "{\"legacy\":[]}", "hash-43",
					"2026-01-01 10:15:31+02");
			insertRow(statement, 44, "v1-out-window-future", "user-v1", "payment.settled", "core-banking",
					"9999-12-31T23:59:59.999999+00", "2026-01-01 10:15:30.123456+02", null, "hash-44",
					"2026-01-01 10:15:31+02");
		}
	}

	private static void insertRow(PreparedStatement statement, int id, String eventId, String userId,
			String eventType, String source, String occurredAt, String receivedAt, String metadata,
			String contentHash, String createdAt) throws SQLException {
		statement.setInt(1, id);
		statement.setString(2, eventId);
		statement.setString(3, userId);
		statement.setString(4, eventType);
		statement.setString(5, source);
		statement.setString(6, occurredAt);
		statement.setString(7, receivedAt);
		statement.setString(8, metadata);
		statement.setString(9, contentHash);
		statement.setString(10, createdAt);
		statement.executeUpdate();
	}

	private static Row readRow(ResultSet result) throws SQLException {
		return new Row(result.getInt("id"), result.getString("event_id"), result.getString("user_id"),
				result.getString("event_type"), result.getString("source"),
				result.getObject("occurred_at", OffsetDateTime.class),
				result.getObject("received_at", OffsetDateTime.class), result.getString("metadata"),
				result.getString("content_hash"), result.getObject("created_at", OffsetDateTime.class));
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

	private static List<String> historyVersions() throws SQLException {
		List<String> versions = new ArrayList<>();
		try (Connection connection = connection();
				ResultSet result = connection.createStatement().executeQuery(
						"SELECT version FROM flyway_schema_history ORDER BY installed_rank")) {
			while (result.next()) {
				versions.add(result.getString("version"));
			}
		}
		return versions;
	}

	private static List<org.assertj.core.groups.Tuple> historyVersionsWithSuccess() throws SQLException {
		List<org.assertj.core.groups.Tuple> entries = new ArrayList<>();
		try (Connection connection = connection();
				ResultSet result = connection.createStatement().executeQuery(
						"SELECT version, success FROM flyway_schema_history ORDER BY installed_rank")) {
			while (result.next()) {
				entries.add(tuple(result.getString("version"), result.getBoolean("success")));
			}
		}
		return entries;
	}

	private static String monthlyPartitionName(YearMonth month) {
		// Same naming as the V2 DO block: to_char(..., 'YYYY_MM').
		return "events_" + "%04d_%02d".formatted(month.getYear(), month.getMonthValue());
	}

}

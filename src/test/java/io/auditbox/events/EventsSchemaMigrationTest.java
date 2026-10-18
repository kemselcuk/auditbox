package io.auditbox.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Stage 2 integration test (plan task T2.1): applies V1__create_events.sql with Flyway to a
 * real PostgreSQL (Testcontainers, same pinned image as docker-compose.yml) on a clean
 * database and asserts the resulting schema. Plain Flyway + JDBC — no Spring context, no JPA
 * entity (Stage 3 owns those).
 */
class EventsSchemaMigrationTest {

	/** Pinned PostgreSQL image; keep in sync with docker-compose.yml. */
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
			.withDatabaseName("auditbox");

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

	@Test
	void flywayAppliesV1ToACleanDatabase() throws SQLException {
		try (Connection connection = connection();
				Statement statement = connection.createStatement();
				ResultSet result = statement
						.executeQuery("SELECT version, description, success FROM flyway_schema_history")) {
			assertThat(result.next()).isTrue();
			assertThat(result.getString("version")).isEqualTo("1");
			assertThat(result.getString("description")).isEqualTo("create events");
			assertThat(result.getBoolean("success")).isTrue();
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
	void eventIdHasTheUniqueConstraint() throws SQLException {
		try (Connection connection = connection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT tc.constraint_name, kcu.column_name
						FROM information_schema.table_constraints tc
						JOIN information_schema.key_column_usage kcu
						  ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
						WHERE tc.table_schema = 'public' AND tc.table_name = 'events'
						  AND tc.constraint_type = 'UNIQUE'""")) {
			Map<String, String> uniqueConstraints = new HashMap<>();
			while (result.next()) {
				uniqueConstraints.put(result.getString("constraint_name"), result.getString("column_name"));
			}
			assertThat(uniqueConstraints).containsEntry("uq_events_event_id", "event_id").hasSize(1);
		}
	}

	@Test
	void eventIdUniqueConstraintRejectsDuplicateEventIds() throws SQLException {
		String insert = """
				INSERT INTO events (event_id, user_id, event_type, source, occurred_at, content_hash)
				VALUES (?, ?, ?, ?, now(), ?)""";
		try (Connection connection = connection();
				PreparedStatement first = connection.prepareStatement(insert);
				PreparedStatement duplicate = connection.prepareStatement(insert)) {
			for (PreparedStatement statement : List.of(first, duplicate)) {
				statement.setString(1, "duplicate-check");
				statement.setString(2, "user-1");
				statement.setString(3, "payment.settled");
				statement.setString(4, "core-banking");
				statement.setString(5, "hash-of-duplicate-check");
			}
			first.executeUpdate();

			try {
				duplicate.executeUpdate();
				throw new AssertionError("duplicate event_id must be rejected by uq_events_event_id");
			}
			catch (SQLException expected) {
				// 23505 = unique_violation; this is exactly the race signal Stage 3 catches (D4).
				assertThat(expected.getSQLState()).isEqualTo("23505");
			}
		}
	}

	@Test
	void keysetAndFilterIndexesExist() throws SQLException {
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

		// Keyset index for the D3 order, backing indexes of the primary key and the D4
		// unique constraint, and the three Stage 4 filter indexes.
		assertThat(indexes).containsKeys("ix_events_occurred_at_event_id", "events_pkey", "uq_events_event_id",
				"ix_events_user_id", "ix_events_event_type", "ix_events_source");
		assertThat(indexes.get("ix_events_occurred_at_event_id")).contains("(occurred_at DESC, event_id DESC)");
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

	private static Connection connection() throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}
}

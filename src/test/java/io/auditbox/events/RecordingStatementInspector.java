package io.auditbox.events;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Test-only statement inspector for the one-SELECT-per-page evidence (T4.1, D3): records
 * the raw SQL of every executed JDBC statement so a test can assert on the statement text
 * itself — e.g. that a page SELECT carries a row limit — which the Hibernate statistics
 * counters used by {@code aPageRequestIssuesExactlyOneSelectWithoutCount} cannot see
 * (a dropped SQL LIMIT would still be exactly one prepared statement).
 *
 * <p>Registered for the {@code EventListApiControllerTest} context via
 * {@code spring.jpa.properties.hibernate.session_factory.statement_inspector}
 * (class-name reference; Hibernate instantiates it through this public no-argument
 * constructor). The collector is shared static state: the assertion clears it right
 * before the request under test and reads it right after, and the request under test is
 * the only statement executed in between.
 */
public class RecordingStatementInspector implements StatementInspector {

	/** SQL of every statement executed since the last {@link #clear()}, in execution order. */
	private static final CopyOnWriteArrayList<String> EXECUTED_SQL = new CopyOnWriteArrayList<>();

	@Override
	public String inspect(String sql) {
		EXECUTED_SQL.add(sql);
		return sql;
	}

	static void clear() {
		EXECUTED_SQL.clear();
	}

	static List<String> recorded() {
		return List.copyOf(EXECUTED_SQL);
	}
}

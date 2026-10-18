package io.auditbox.events;

import java.time.Instant;

/**
 * The supported occurrence range shared by the write and read paths (D1, D13): every
 * {@code occurredAt} written by this API version, and every queried bound or cursor position,
 * must fall inside it. Keeping a single definition makes cursor encoding and decoding
 * symmetric — the write path cannot store a position the read path would refuse (review round
 * 3b, F-1). Rows inserted out-of-band (or by versions before this validation existed) with an
 * out-of-range {@code occurredAt} are not covered by that guarantee and would need a data
 * migration or removal. The database can represent a wider range; this is the API contract,
 * not a storage limit.
 */
final class EventTimeRange {

	/** Inclusive lower bound of the supported occurrence range. */
	static final Instant MIN = Instant.parse("0001-01-01T00:00:00Z");

	/** Inclusive upper bound of the supported occurrence range. */
	static final Instant MAX = Instant.parse("9999-12-31T23:59:59.999999Z");

	/** The range rendered for API messages and documentation. */
	static final String TEXT = "0001-01-01T00:00:00Z..9999-12-31T23:59:59.999999Z";

	private EventTimeRange() {
	}

	/** Whether the instant is inside the inclusive supported range. */
	static boolean supports(Instant instant) {
		return !instant.isBefore(MIN) && !instant.isAfter(MAX);
	}
}

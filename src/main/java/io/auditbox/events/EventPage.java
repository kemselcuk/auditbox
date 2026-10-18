package io.auditbox.events;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response envelope of GET /api/v1/events (plan task T4.1, decision D3): the events of one
 * page in keyset order (occurredAt DESC, eventId DESC) and the opaque cursor to the next
 * page. There is intentionally no total count (D3): {@code nextCursor} is the only
 * continuation signal and is non-null exactly when more results exist.
 */
@Schema(description = "A page of events in keyset order (occurredAt DESC, eventId DESC)")
public record EventPage(

		@Schema(description = "The events of this page")
		List<EventResponse> items,

		@Schema(description = "Opaque continuation cursor over the last item's (occurredAt, eventId); echo it "
				+ "verbatim as the cursor query parameter to fetch the next page. Null on the last page.",
				nullable = true,
				example = "eyJvY2N1cnJlZEF0IjoiMjAyNi0xMC0xOFQxMjowMDowMC4xMjM0NTZaIiwiZXZlbnRJZCI6IjhiMGMwZjYxLTk3YTEt"
						+ "NGUwYS05ZjU2LTJmN2QzZjNiMmMxMCJ9")
		String nextCursor) {
}

package io.auditbox.events;

import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * RFC 9457 Problem Details handling for the events API (plan task T3.1):
 *
 * <ul>
 *   <li>400 for Bean Validation failures — with a field-level {@code errors} extension — and
 *       for unreadable or malformed bodies (broken JSON, wrong types, oversized number
 *       literals),</li>
 *   <li>400 for invalid list query parameters (T4.1) via {@link InvalidEventQueryException}:
 *       a malformed cursor, a non-ISO-8601 occurredFrom/occurredTo, an inverted date range
 *       or a page size outside 1..100 — the violation is the detail,</li>
 *   <li>409 for a duplicate eventId with different content, carrying the stored record in the
 *       {@code existingEvent} extension in exactly the shape of a success response (D4),</li>
 *   <li>400 for request content PostgreSQL rejects as a "data exception" (SQLState class 22,
 *       review round 2 F3) — e.g. a NUL character in a text column or inside a jsonb
 *       string — with a generic detail,</li>
 *   <li>a generic 500 for anything unexpected: the body contains no internals, the stack
 *       trace goes to the log only.</li>
 * </ul>
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} keeps the framework's standard 4xx
 * handling (unsupported media type, wrong method, ...) for this controller in the same
 * Problem Details shape.
 */
@RestControllerAdvice(assignableTypes = EventController.class)
public class EventApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(EventApiExceptionHandler.class);

	/**
	 * SQLState class 22 ("data exception"): PostgreSQL rejects some request content only at
	 * the database boundary, e.g. 22003 numeric value out of range, 22021 character not in
	 * repertoire (a NUL byte in a text column) or 22P05 untransmittable/unsupported Unicode
	 * escape (a NUL character inside a {@code jsonb} string). Such a violation means the
	 * request content is unrepresentable and therefore a client error (review round 2 F3).
	 */
	private static final String SQLSTATE_CLASS_DATA_EXCEPTION = "22";

	/**
	 * Validation failures (T3.1): 400 with one entry per violated field in the
	 * {@code errors} extension.
	 */
	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<FieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
				.map(fieldError -> new FieldViolation(fieldError.getField(), fieldError.getDefaultMessage()))
				.sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message))
				.toList();
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
				"Request validation failed");
		problem.setTitle("Bad Request");
		problem.setProperty("errors", errors);
		return handleExceptionInternal(ex, problem, headers, status, request);
	}

	/**
	 * Unreadable or malformed request body (T3.1): 400. The Jackson exception message can
	 * quote internals, so the response keeps a generic detail.
	 */
	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
				"Malformed or unreadable request body");
		problem.setTitle("Bad Request");
		return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
	}

	/**
	 * Invalid list query parameters (T4.1): a malformed cursor, a non-ISO-8601
	 * occurredFrom/occurredTo, an inverted date range or a page size outside 1..100 —
	 * 400 Problem Details with the violation as the detail. No {@code errors} extension:
	 * that shape is reserved for the Bean Validation failures of a request body.
	 */
	@ExceptionHandler(InvalidEventQueryException.class)
	public ResponseEntity<Object> handleInvalidEventQuery(InvalidEventQueryException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setTitle("Bad Request");
		return ResponseEntity.badRequest().body(problem);
	}

	/**
	 * Duplicate eventId with different content (D4): 409 with the stored record in the
	 * {@code existingEvent} extension, same shape as a success response.
	 */
	@ExceptionHandler(EventConflictException.class)
	public ResponseEntity<Object> handleConflict(EventConflictException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setTitle("Conflict");
		problem.setProperty("existingEvent", EventResponse.from(ex.getExistingEvent()));
		return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
	}

	/**
	 * Client-caused SQL data errors (review round 2 F3): request content PostgreSQL rejects
	 * at insert time — a NUL character in a text column (22021) or inside a jsonb string
	 * (22P05), a numeric overflowing the column (22003), and the like — surfaces here as a
	 * {@link DataIntegrityViolationException}. When any {@link SQLException} in the cause
	 * chain carries SQLState class 22 the request data itself is the problem: 400 with a
	 * generic detail. Any other integrity cause keeps the generic 500. The idempotency
	 * duplicate case (23505, D4) is resolved inside {@link EventService} and never reaches
	 * this handler.
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<Object> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
		if (hasSqlStateClass(ex, SQLSTATE_CLASS_DATA_EXCEPTION)) {
			ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
					"Event content could not be stored");
			problem.setTitle("Bad Request");
			return ResponseEntity.badRequest().body(problem);
		}
		// Not attributable to the request: unexpected error handling (generic 500, T3.1).
		return handleUnexpected(ex);
	}

	/**
	 * Checks whether any {@link SQLException} in the cause chain carries an SQLState of the
	 * given class (first two state characters); the translated Spring exception nests the
	 * driver exception several layers deep and the state lives on the driver exception.
	 */
	private static boolean hasSqlStateClass(Throwable thrown, String sqlStateClass) {
		Throwable cause = thrown;
		while (cause != null) {
			if (cause instanceof SQLException sqlException
					&& sqlException.getSQLState() != null
					&& sqlException.getSQLState().startsWith(sqlStateClass)) {
				return true;
			}
			cause = cause.getCause();
		}
		return false;
	}

	/**
	 * Anything unexpected: generic 500 without internals; the details are logged (T3.1).
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<Object> handleUnexpected(Exception ex) {
		log.error("Unhandled exception while handling an events API request", ex);
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
				"An unexpected error occurred");
		problem.setTitle("Internal Server Error");
		return ResponseEntity.internalServerError().body(problem);
	}

	/**
	 * One field violation inside the {@code errors} extension. RFC 9457 leaves extension
	 * shapes open; this shape is part of the API contract (T3.1).
	 */
	private record FieldViolation(String field, String message) {
	}
}

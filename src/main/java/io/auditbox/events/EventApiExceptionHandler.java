package io.auditbox.events;

import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *   <li>409 for a duplicate eventId with different content, carrying the stored record in the
 *       {@code existingEvent} extension in exactly the shape of a success response (D4),</li>
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

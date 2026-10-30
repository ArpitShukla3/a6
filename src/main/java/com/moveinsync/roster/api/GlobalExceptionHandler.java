package com.moveinsync.roster.api;

import com.moveinsync.roster.error.RosterException;
import com.moveinsync.roster.observability.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.List;

/**
 * Every error is RFC 7807 problem+json with a stable code and the correlation id. Messages are
 * fixed texts; submitted values, SQL errors and stack traces never reach the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RosterException.class)
    ResponseEntity<ProblemDetail> roster(RosterException e) {
        return problem(e.status(), e.code(), e.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalid(MethodArgumentNotValidException e) {
        List<String> fields = e.getBindingResult().getFieldErrors().stream().map(f -> f.getField()).distinct().toList();
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request fields are invalid", fields);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class, MissingRequestHeaderException.class})
    ResponseEntity<ProblemDetail> unreadable(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request could not be read", null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> mediaType(Exception e) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Unsupported content type", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> method(Exception e) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Method not allowed", null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> noResource(Exception e) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found", null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> denied(AccessDeniedException e) {
        return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed for this role", null);
    }

    @ExceptionHandler(InvalidBearerTokenException.class)
    ResponseEntity<ProblemDetail> badToken(Exception e) {
        return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "A valid bearer token is required", null);
    }

    @ExceptionHandler({CannotGetJdbcConnectionException.class, TransientDataAccessException.class})
    ResponseEntity<ProblemDetail> unavailable(DataAccessException e) {
        log.error("dependency unavailable error={}", e.getClass().getSimpleName());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "Temporarily unavailable, retry later", null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        // Class and top frame only: exception messages (e.g. from the driver) can contain input values.
        StackTraceElement top = e.getStackTrace().length > 0 ? e.getStackTrace()[0] : null;
        log.error("unexpected error error={} at={}", e.getClass().getName(), top);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error", null);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail, List<String> fields) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("urn:roster:error:" + code));
        p.setProperty("code", code);
        p.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
        if (fields != null) {
            p.setProperty("fields", fields);
        }
        return ResponseEntity.status(status).body(p);
    }
}

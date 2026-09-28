package com.leadmanagement.infrastructure.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.persistence.OptimisticLockException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Global exception handler.
 *
 * Returns structured JSON error responses instead of Spring's default HTML error page.
 * Every error response includes a correlationId so clients can reference it in support tickets.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles @Valid validation failures - returns 400 with field-level error details.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult()
            .getFieldErrors()
            .stream()
            .map(e -> e.getField() + ": " + e.getDefaultMessage())
            .toList();

        return ResponseEntity.badRequest().body(errorBody(400, "Validation failed", errors));
    }

    /**
     * Handles invalid UUID or bad request arguments - returns 400.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(errorBody(400, ex.getMessage(), null));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied [correlationId={}]: {}", CorrelationIdFilter.current(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(errorBody(403, "Access denied", null));
    }

    @ExceptionHandler({OptimisticLockException.class, ObjectOptimisticLockingFailureException.class})
    public ResponseEntity<Map<String, Object>> handleOptimisticLock(Exception ex) {
        log.warn("Optimistic lock conflict [correlationId={}]: {}", CorrelationIdFilter.current(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(errorBody(409, "Conflict: entity was modified by another request", null));
    }

    /**
     * Catch-all for unexpected errors - returns 500 without leaking internal details.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("Unexpected error [correlationId={}]: {}", CorrelationIdFilter.current(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(errorBody(500, "An unexpected error occurred", null));
    }

    private Map<String, Object> errorBody(int status, String message, List<String> errors) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("status", status);
        body.put("message", message);
        body.put("correlationId", CorrelationIdFilter.current());
        body.put("timestamp", LocalDateTime.now().toString());
        if (errors != null && !errors.isEmpty()) {
            body.put("errors", errors);
        }
        return body;
    }
}


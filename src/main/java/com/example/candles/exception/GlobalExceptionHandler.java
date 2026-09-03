package com.example.candles.exception;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidRoundTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidToken(InvalidRoundTokenException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler({InvalidCredentialsException.class, InvalidRefreshTokenException.class})
    public ResponseEntity<ErrorResponse> handleUnauthorized(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(GuessOutOfTimeException.class)
    public ResponseEntity<ErrorResponse> handleOutOfTime(GuessOutOfTimeException e) {
        return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ErrorResponse> handleRateLimited(TooManyRequestsException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
    }

    /**
     * Constraints on a method parameter — {@code @Pattern} on a {@code @RequestParam} and the
     * like — throw this rather than the MethodArgumentNotValidException a {@code @RequestBody}
     * raises, and without a handler it fell through as a 500. A malformed wallet address is the
     * caller's mistake, not the server's, and answering it with an internal error both misleads
     * the caller and fills the log with stack traces for ordinary bad input.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleInvalidParameter(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .findFirst()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .orElse("Tham số không hợp lệ.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(detail));
    }

    public record ErrorResponse(String message) {
    }
}

package com.example.candles.api;

import com.example.candles.auth.InvalidCredentialsException;
import com.example.candles.auth.InvalidRefreshTokenException;
import com.example.candles.round.GuessOutOfTimeException;
import com.example.candles.round.InvalidRoundTokenException;
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

    public record ErrorResponse(String message) {
    }
}

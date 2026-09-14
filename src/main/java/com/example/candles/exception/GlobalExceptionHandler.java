package com.example.candles.exception;

import com.example.candles.client.ExchangeCoolingDownException;
import com.example.candles.service.RecentErrors;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import java.time.temporal.ChronoUnit;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final RecentErrors recentErrors;

    public GlobalExceptionHandler(RecentErrors recentErrors) {
        this.recentErrors = recentErrors;
    }

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

    /**
     * An exchange or market-data call that failed is not this server's bug, and a bare 500 said
     * nothing about which of the two it was. The first deploy made that concrete: every candle
     * sync and the live round failed on a host that could not reach the exchange, and the log
     * viewer showed a hundred lines of filter chain with the one line naming the cause scrolled
     * out of reach. So the reason travels in two places a person can actually find — a single
     * log line, and the response body.
     *
     * The body carries only the upstream status or the failure's type, never the upstream's
     * own message, which is not ours to republish.
     */
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ErrorResponse> handleUpstreamFailure(RestClientException e, HttpServletRequest request) {
        String reason = upstreamReason(e);
        String cause = NestedExceptionUtils.getMostSpecificCause(e).toString();
        log.warn("Upstream request failed: {} ({})", reason, cause);
        recentErrors.record("upstream", request.getMethod() + " " + request.getRequestURI(), reason + " — " + cause);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("Không lấy được dữ liệu từ sàn (" + reason + ")."));
    }

    static String upstreamReason(RestClientException e) {
        if (e instanceof ExchangeCoolingDownException coolingDown) {
            return "sàn đang tạm chặn, thử lại sau " + coolingDown.until().truncatedTo(ChronoUnit.SECONDS);
        }
        if (e instanceof RestClientResponseException response) {
            return "HTTP " + response.getStatusCode().value();
        }
        return NestedExceptionUtils.getMostSpecificCause(e).getClass().getSimpleName();
    }

    public record ErrorResponse(String message) {
    }
}

package com.example.candles.exception;

import com.example.candles.client.ExchangeCoolingDownException;
import com.example.candles.service.RecentErrors;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GlobalExceptionHandlerTest {

    private final RecentErrors recentErrors = new RecentErrors(Clock.fixed(Instant.parse("2026-09-14T10:00:00Z"), ZoneOffset.UTC));
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(recentErrors);
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/live/round");

    @Test
    void anExchangeThatRefusesTheCallerIsReportedByItsStatus() {
        HttpClientErrorException refused = HttpClientErrorException.create(
                HttpStatusCode.valueOf(451), "", HttpHeaders.EMPTY,
                "{\"msg\":\"Service unavailable from a restricted location\"}".getBytes(), null);

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleUpstreamFailure(refused, request);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("Không lấy được dữ liệu từ sàn (HTTP 451).", response.getBody().message());
    }

    @Test
    void anExchangeThatCannotBeReachedIsReportedByTheRootCause() {
        ResourceAccessException unreachable = new ResourceAccessException(
                "I/O error on GET request", new IOException("wrapped", new UnknownHostException("api.binance.com")));

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleUpstreamFailure(unreachable, request);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("Không lấy được dữ liệu từ sàn (UnknownHostException).", response.getBody().message());
    }

    @Test
    void theUpstreamsOwnMessageIsNotRepublished() {
        HttpClientErrorException refused = HttpClientErrorException.create(
                HttpStatusCode.valueOf(403), "Forbidden", HttpHeaders.EMPTY, "secret upstream detail".getBytes(), null);

        String message = handler.handleUpstreamFailure(refused, request).getBody().message();

        assertFalse(message.contains("secret upstream detail"));
        assertFalse(message.contains("Forbidden"));
    }

    @Test
    void aBackOffNamesWhenTheExchangeWillBeAskedAgain() {
        ExchangeCoolingDownException coolingDown = new ExchangeCoolingDownException(Instant.parse("2026-09-13T14:05:00.123Z"));

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleUpstreamFailure(coolingDown, request);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("Không lấy được dữ liệu từ sàn (sàn đang tạm chặn, thử lại sau 2026-09-13T14:05:00Z).",
                response.getBody().message());
    }

    @Test
    void anUpstreamFailureIsListedForTheOpsPaneWithTheRequestItHappenedIn() {
        handler.handleUpstreamFailure(HttpClientErrorException.create(
                HttpStatusCode.valueOf(418), "", HttpHeaders.EMPTY, null, null), request);

        var listed = recentErrors.snapshot();
        assertEquals(1, listed.size());
        assertEquals("upstream", listed.getFirst().source());
        assertEquals("GET /api/live/round", listed.getFirst().where());
        assertEquals(true, listed.getFirst().summary().startsWith("HTTP 418"));
    }
}

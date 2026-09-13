package com.example.candles.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import java.io.IOException;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void anExchangeThatRefusesTheCallerIsReportedByItsStatus() {
        HttpClientErrorException refused = HttpClientErrorException.create(
                HttpStatusCode.valueOf(451), "", HttpHeaders.EMPTY,
                "{\"msg\":\"Service unavailable from a restricted location\"}".getBytes(), null);

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleUpstreamFailure(refused);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("Không lấy được dữ liệu từ sàn (HTTP 451).", response.getBody().message());
    }

    @Test
    void anExchangeThatCannotBeReachedIsReportedByTheRootCause() {
        ResourceAccessException unreachable = new ResourceAccessException(
                "I/O error on GET request", new IOException("wrapped", new UnknownHostException("api.binance.com")));

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleUpstreamFailure(unreachable);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("Không lấy được dữ liệu từ sàn (UnknownHostException).", response.getBody().message());
    }

    @Test
    void theUpstreamsOwnMessageIsNotRepublished() {
        HttpClientErrorException refused = HttpClientErrorException.create(
                HttpStatusCode.valueOf(403), "Forbidden", HttpHeaders.EMPTY, "secret upstream detail".getBytes(), null);

        String message = handler.handleUpstreamFailure(refused).getBody().message();

        assertFalse(message.contains("secret upstream detail"));
        assertFalse(message.contains("Forbidden"));
    }
}

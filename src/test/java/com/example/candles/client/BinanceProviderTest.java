package com.example.candles.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BinanceProviderTest {

    private static final Instant T0 = Instant.parse("2026-09-13T13:00:00Z");
    private static final Instant FROM = Instant.parse("2026-09-13T10:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-13T12:59:59Z");

    private MockRestServiceServer server;
    private MovableClock clock;
    private BinanceProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://exchange.test");
        server = MockRestServiceServer.bindTo(builder).build();
        clock = new MovableClock(T0);
        provider = new BinanceProvider(builder.build(), clock);
    }

    @Test
    void aBanStopsFurtherCallsUntilTheRetryAfterTheExchangeNamed() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "300");
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith("http://exchange.test/api/v3/klines")))
                .andRespond(withStatus(HttpStatusCode.valueOf(418)).headers(headers));

        assertThrows(HttpClientErrorException.class, () -> provider.fetchCandles("BTCUSDT", "1h", FROM, TO));

        // Still inside the ban: refused locally, and the mock would fail on a second request.
        clock.advance(Duration.ofSeconds(299));
        ExchangeCoolingDownException refused = assertThrows(ExchangeCoolingDownException.class,
                () -> provider.fetchCandles("BTCUSDT", "1h", FROM, TO));
        assertEquals(T0.plusSeconds(300), refused.until());
        server.verify();

        // Past it: the exchange is asked again.
        server.reset();
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith("http://exchange.test/api/v3/klines")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        clock.advance(Duration.ofSeconds(2));
        assertEquals(0, provider.fetchCandles("BTCUSDT", "1h", FROM, TO).size());
        server.verify();
    }

    @Test
    void aRateLimitWithoutRetryAfterBacksOffForTheShortestBan() {
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith("http://exchange.test/api/v3/klines")))
                .andRespond(withStatus(HttpStatusCode.valueOf(429)));

        assertThrows(HttpClientErrorException.class, () -> provider.fetchCandles("ETHUSDT", "1h", FROM, TO));

        ExchangeCoolingDownException refused = assertThrows(ExchangeCoolingDownException.class,
                () -> provider.fetchCandles("ETHUSDT", "1h", FROM, TO));
        assertEquals(T0.plus(BinanceProvider.DEFAULT_BACKOFF), refused.until());
        server.verify();
    }

    @Test
    void anOrdinaryClientErrorDoesNotBackOff() {
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith("http://exchange.test/api/v3/klines")))
                .andRespond(withStatus(HttpStatusCode.valueOf(400)));
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith("http://exchange.test/api/v3/klines")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThrows(HttpClientErrorException.class, () -> provider.fetchCandles("NOPE", "1h", FROM, TO));
        assertEquals(0, provider.fetchCandles("BTCUSDT", "1h", FROM, TO).size());
        server.verify();
    }

    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

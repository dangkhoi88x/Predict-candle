package com.example.candles.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.startsWith;

class OkxProviderTest {

    private static final long HOUR = Duration.ofHours(1).toMillis();
    private static final Instant FROM = Instant.parse("2025-01-01T00:00:00Z");

    private MockRestServiceServer server;
    private OkxProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://okx.test");
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new OkxProvider(builder.build());
    }

    @Test
    void pairsAndTimeframesAreTranslatedToOkxsNames() {
        assertEquals("BTC-USDT", OkxProvider.instrumentId("BTCUSDT"));
        assertEquals("ETH-BTC", OkxProvider.instrumentId("ETHBTC"));
        assertEquals("1H", OkxProvider.bar("1h"));
        assertEquals("15m", OkxProvider.bar("15m"));
        assertEquals("4H", OkxProvider.bar("4h"));
        // From six hours up a plain OKX bar is aligned to Hong Kong time, not the epoch.
        assertEquals("12Hutc", OkxProvider.bar("12h"));
        assertEquals("1Dutc", OkxProvider.bar("1d"));
        assertThrows(IllegalArgumentException.class, () -> OkxProvider.instrumentId("USDT"));
    }

    @Test
    void pagesWalkBackwardsAndComeOutOldestFirstWithinTheRange() {
        // 400 hourly candles: one full page of 300, then 100 older ones.
        Instant to = FROM.plusMillis(399 * HOUR);
        long toMillis = to.toEpochMilli();
        long firstPageOldest = toMillis - 299 * HOUR;

        server.expect(once(), requestTo(startsWith("http://okx.test/api/v5/market/history-candles")))
                .andExpect(queryParam("instId", "BTC-USDT"))
                .andExpect(queryParam("bar", "1H"))
                .andExpect(queryParam("after", String.valueOf(toMillis + 1)))
                .andExpect(queryParam("before", String.valueOf(FROM.toEpochMilli() - 1)))
                .andRespond(withSuccess(page(toMillis, firstPageOldest), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith("http://okx.test/api/v5/market/history-candles")))
                .andExpect(queryParam("after", String.valueOf(firstPageOldest)))
                .andRespond(withSuccess(page(firstPageOldest - HOUR, FROM.toEpochMilli()), MediaType.APPLICATION_JSON));

        List<CandleData> candles = provider.fetchCandles("BTCUSDT", "1h", FROM, to);

        server.verify();
        assertEquals(400, candles.size());
        assertEquals(FROM, candles.getFirst().openTime());
        assertEquals(to, candles.getLast().openTime());
        for (int i = 1; i < candles.size(); i++) {
            assertEquals(HOUR, candles.get(i).openTime().toEpochMilli() - candles.get(i - 1).openTime().toEpochMilli());
        }
        assertEquals(new BigDecimal("12.5"), candles.getFirst().volume());
    }

    @Test
    void anErrorCodeInsideA200IsAFailureNotAnEmptyChart() {
        server.expect(once(), requestTo(startsWith("http://okx.test/api/v5/market/history-candles")))
                .andRespond(withSuccess("{\"code\":\"51001\",\"data\":[],\"msg\":\"Instrument ID doesn't exist.\"}",
                        MediaType.APPLICATION_JSON));

        assertThrows(RestClientException.class,
                () -> provider.fetchCandles("NOPEUSDT", "1h", FROM, FROM.plusMillis(10 * HOUR)));
        server.verify();
    }

    @Test
    void anEmptyRangeIsAnEmptyList() {
        server.expect(once(), requestTo(startsWith("http://okx.test/api/v5/market/history-candles")))
                .andRespond(withSuccess("{\"code\":\"0\",\"data\":[],\"msg\":\"\"}", MediaType.APPLICATION_JSON));

        assertEquals(0, provider.fetchCandles("SOLUSDT", "1h", FROM, FROM.plusMillis(HOUR)).size());
        server.verify();
    }

    /** OKX rows newest first, from {@code newest} down to {@code oldest} inclusive, one per hour. */
    private static String page(long newest, long oldest) {
        String rows = LongStream.iterate(newest, t -> t >= oldest, t -> t - HOUR)
                .mapToObj(t -> "[\"" + t + "\",\"100\",\"110\",\"90\",\"105\",\"12.5\",\"1312.5\",\"1312.5\",\"1\"]")
                .collect(Collectors.joining(","));
        return "{\"code\":\"0\",\"data\":[" + rows + "],\"msg\":\"\"}";
    }
}

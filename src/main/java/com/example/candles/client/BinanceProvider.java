package com.example.candles.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "candles", name = "price-source", havingValue = "binance", matchIfMissing = true)
public class BinanceProvider implements PriceDataProvider {

    private static final int PAGE_LIMIT = 1000;

    /** Binance's shortest ban, used when a 418 or 429 arrives without a Retry-After header. */
    static final Duration DEFAULT_BACKOFF = Duration.ofMinutes(2);

    private final RestClient restClient;
    private final Clock clock;

    /**
     * Binance answers 429 when an address uses too much request weight and 418 once it has kept
     * going after that — an IP ban that runs from two minutes to three days and grows for
     * addresses that keep offending. On a host with shared outbound addresses the ban can arrive
     * without this app having sent much at all, and the first deploy on Render met exactly that.
     *
     * What this app must not do is keep asking. Every page polls the live round, every poll that
     * misses the price cache is a request, and each one sent into a ban is the behaviour the ban
     * lengthens for. So a 418 or 429 stops outbound calls until the Retry-After the exchange named.
     * Per instance and in memory: a restart forgets it, which costs one more refused request.
     */
    private volatile Instant coolingDownUntil = Instant.EPOCH;

    public BinanceProvider(RestClient binanceRestClient, Clock clock) {
        this.restClient = binanceRestClient;
        this.clock = clock;
    }

    @Override
    public List<CandleData> fetchCandles(String symbol, String timeframe, Instant from, Instant to) {
        long intervalMillis = Timeframes.parse(timeframe).toMillis();
        long cursor = from.toEpochMilli();
        long endTime = to.toEpochMilli();
        List<CandleData> result = new ArrayList<>();

        while (cursor < endTime) {
            List<CandleData> page = fetchPage(symbol, timeframe, cursor, endTime, PAGE_LIMIT);
            if (page.isEmpty()) {
                break;
            }
            result.addAll(page);

            long nextCursor = page.get(page.size() - 1).openTime().toEpochMilli() + intervalMillis;
            if (nextCursor <= cursor) {
                break;
            }
            cursor = nextCursor;

            if (page.size() < PAGE_LIMIT) {
                break;
            }
        }
        return result;
    }

    private List<CandleData> fetchPage(String symbol, String interval, long startTime, long endTime, int limit) {
        Instant now = clock.instant();
        if (now.isBefore(coolingDownUntil)) {
            throw new ExchangeCoolingDownException(coolingDownUntil);
        }

        JsonNode root;
        try {
            root = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/v3/klines")
                            .queryParam("symbol", symbol)
                            .queryParam("interval", interval)
                            .queryParam("startTime", startTime)
                            .queryParam("endTime", endTime)
                            .queryParam("limit", limit)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 418 || status == 429) {
                coolingDownUntil = now.plus(retryAfter(e));
            }
            throw e;
        }

        List<CandleData> candles = new ArrayList<>();
        if (root == null) {
            return candles;
        }
        for (JsonNode entry : root) {
            candles.add(new CandleData(
                    Instant.ofEpochMilli(entry.get(0).asLong()),
                    new BigDecimal(entry.get(1).asText()),
                    new BigDecimal(entry.get(2).asText()),
                    new BigDecimal(entry.get(3).asText()),
                    new BigDecimal(entry.get(4).asText()),
                    new BigDecimal(entry.get(5).asText())
            ));
        }
        return candles;
    }

    /** Retry-After in seconds, which is the form Binance sends; anything else gets the default. */
    static Duration retryAfter(HttpClientErrorException e) {
        HttpHeaders headers = e.getResponseHeaders();
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value != null) {
            try {
                long seconds = Long.parseLong(value.trim());
                if (seconds > 0) {
                    return Duration.ofSeconds(seconds);
                }
            } catch (NumberFormatException ignored) {
                // An HTTP-date form is legal but not what Binance sends; fall through.
            }
        }
        return DEFAULT_BACKOFF;
    }
}

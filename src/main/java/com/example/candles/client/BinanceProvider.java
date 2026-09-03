package com.example.candles.client;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class BinanceProvider implements PriceDataProvider {

    private static final int PAGE_LIMIT = 1000;

    private final RestClient restClient;

    public BinanceProvider(RestClient binanceRestClient) {
        this.restClient = binanceRestClient;
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
        JsonNode root = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v3/klines")
                        .queryParam("symbol", symbol)
                        .queryParam("interval", interval)
                        .queryParam("startTime", startTime)
                        .queryParam("endTime", endTime)
                        .queryParam("limit", limit)
                        .build())
                .retrieve()
                .body(JsonNode.class);

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

}

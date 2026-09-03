package com.example.candles.client;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.example.candles.dto.MarketQuote;

/**
 * Pulls live price/change quotes from Yahoo Finance's unofficial "spark" endpoint. It caps
 * out at 20 symbols per request and has no CORS headers, so this has to run server-side —
 * larger constituent lists are automatically split into multiple batched requests.
 */
@Component
public class YahooFinanceClient {

    private static final int MAX_SYMBOLS_PER_REQUEST = 20;

    private final RestClient restClient;

    public YahooFinanceClient(RestClient yahooFinanceRestClient) {
        this.restClient = yahooFinanceRestClient;
    }

    public List<MarketQuote> fetchQuotes(List<SP500Constituent> constituents) {
        List<MarketQuote> quotes = new ArrayList<>();
        for (int i = 0; i < constituents.size(); i += MAX_SYMBOLS_PER_REQUEST) {
            List<SP500Constituent> batch = constituents.subList(i, Math.min(i + MAX_SYMBOLS_PER_REQUEST, constituents.size()));
            quotes.addAll(fetchBatch(batch));
        }
        return quotes;
    }

    private List<MarketQuote> fetchBatch(List<SP500Constituent> constituents) {
        Map<String, SP500Constituent> bySymbol = constituents.stream()
                .collect(Collectors.toMap(SP500Constituent::symbol, c -> c));
        String symbols = String.join(",", bySymbol.keySet());

        JsonNode root = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/v7/finance/spark")
                        .queryParam("symbols", symbols)
                        .queryParam("range", "5d")
                        .queryParam("interval", "1h")
                        .build())
                .retrieve()
                .body(JsonNode.class);

        List<MarketQuote> quotes = new ArrayList<>();
        if (root == null) return quotes;
        JsonNode spark = root.get("spark");
        JsonNode result = spark == null ? null : spark.get("result");
        if (result == null || !result.isArray()) return quotes;

        for (JsonNode entry : result) {
            JsonNode symbolNode = entry.get("symbol");
            if (symbolNode == null) continue;
            SP500Constituent constituent = bySymbol.get(symbolNode.asText());
            if (constituent == null) continue;

            JsonNode responses = entry.get("response");
            if (responses == null || !responses.isArray() || responses.isEmpty()) continue;
            JsonNode meta = responses.get(0).get("meta");
            if (meta == null) continue;

            JsonNode priceNode = meta.get("regularMarketPrice");
            JsonNode prevCloseNode = meta.get("previousClose");
            if (priceNode == null || prevCloseNode == null) continue;

            double price = priceNode.asDouble();
            double prevClose = prevCloseNode.asDouble();
            if (prevClose <= 0) continue;

            BigDecimal changePercent = BigDecimal.valueOf((price - prevClose) / prevClose * 100)
                    .setScale(2, RoundingMode.HALF_UP);

            quotes.add(new MarketQuote(
                    constituent.symbol(),
                    constituent.name(),
                    constituent.sector(),
                    BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP),
                    changePercent,
                    constituent.approxMarketCapUsd(),
                    extractSparkline(responses.get(0))
            ));
        }
        return quotes;
    }

    /**
     * Pulls the hourly close-price series out of a spark response entry, dropping the nulls
     * Yahoo leaves for intervals outside market hours (pre/post-market gaps, weekends).
     */
    private List<BigDecimal> extractSparkline(JsonNode response) {
        JsonNode indicators = response.get("indicators");
        JsonNode quoteArray = indicators == null ? null : indicators.get("quote");
        JsonNode quote = quoteArray != null && !quoteArray.isEmpty() ? quoteArray.get(0) : null;
        JsonNode closes = quote == null ? null : quote.get("close");
        if (closes == null || !closes.isArray()) {
            return List.of();
        }

        List<BigDecimal> sparkline = new ArrayList<>();
        for (JsonNode value : closes) {
            if (value != null && !value.isNull()) {
                sparkline.add(BigDecimal.valueOf(value.asDouble()).setScale(2, RoundingMode.HALF_UP));
            }
        }
        return sparkline;
    }
}

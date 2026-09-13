package com.example.candles.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Candles from OKX, selected with {@code candles.price-source=okx}.
 *
 * It exists because of where the demo runs, not because Binance is wrong. Render's outbound
 * addresses are shared, Binance bans by address, and it kept banning the Singapore range for
 * traffic this app did not send — 45 minutes, then two hours, on the way to a limit of three
 * days. OKX serves the same pairs without that.
 *
 * Three differences from Binance that this class exists to hide:
 * <ul>
 *   <li>Instruments are dashed: {@code BTCUSDT} is {@code BTC-USDT}.</li>
 *   <li>Pages come newest first and walk backwards from {@code after}; both bounds exclude their
 *       own timestamp.</li>
 *   <li>An error is HTTP 200 with a non-zero {@code code}, so {@code retrieve()} alone would
 *       read an unknown pair as an empty chart.</li>
 * </ul>
 *
 * Volume is column 5, which for spot is in the base asset — the same unit Binance reports, so
 * nothing downstream that multiplies it by a close has to know which source it came from.
 */
@Component
@ConditionalOnProperty(prefix = "candles", name = "price-source", havingValue = "okx")
public class OkxProvider implements PriceDataProvider {

    static final int PAGE_LIMIT = 300;

    /**
     * history-candles allows 20 requests per 2 seconds per address. A first backfill is about fifty
     * pages per pair, so pacing them is what keeps it inside that rather than finding out.
     */
    static final long PAGE_PAUSE_MILLIS = 150;

    private static final List<String> QUOTES = List.of("USDT", "USDC", "BTC", "ETH");

    private final RestClient restClient;

    public OkxProvider(RestClient okxRestClient) {
        this.restClient = okxRestClient;
    }

    /** Candles whose open time lies in [from, to], oldest first — the contract Binance's endTime gives. */
    @Override
    public List<CandleData> fetchCandles(String symbol, String timeframe, Instant from, Instant to) {
        String instrument = instrumentId(symbol);
        String bar = bar(timeframe);
        long fromMillis = from.toEpochMilli();
        long toMillis = to.toEpochMilli();

        List<CandleData> newestFirst = new ArrayList<>();
        long after = toMillis + 1;
        while (true) {
            JsonNode rows = fetchPage(instrument, bar, after, fromMillis - 1);
            if (rows.isEmpty()) {
                break;
            }
            for (JsonNode row : rows) {
                newestFirst.add(new CandleData(
                        Instant.ofEpochMilli(row.get(0).asLong()),
                        new BigDecimal(row.get(1).asText()),
                        new BigDecimal(row.get(2).asText()),
                        new BigDecimal(row.get(3).asText()),
                        new BigDecimal(row.get(4).asText()),
                        new BigDecimal(row.get(5).asText())
                ));
            }
            long oldest = rows.get(rows.size() - 1).get(0).asLong();
            if (rows.size() < PAGE_LIMIT || oldest <= fromMillis || oldest >= after) {
                break;
            }
            after = oldest;
            pause();
        }

        List<CandleData> oldestFirst = new ArrayList<>(newestFirst.size());
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            CandleData candle = newestFirst.get(i);
            long openMillis = candle.openTime().toEpochMilli();
            if (openMillis >= fromMillis && openMillis <= toMillis) {
                oldestFirst.add(candle);
            }
        }
        return oldestFirst;
    }

    private JsonNode fetchPage(String instrument, String bar, long after, long before) {
        JsonNode root = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v5/market/history-candles")
                        .queryParam("instId", instrument)
                        .queryParam("bar", bar)
                        .queryParam("after", after)
                        .queryParam("before", before)
                        .queryParam("limit", PAGE_LIMIT)
                        .build())
                .retrieve()
                .body(JsonNode.class);
        if (root == null) {
            throw new RestClientException("OKX returned an empty body for " + instrument);
        }
        String code = root.path("code").asText();
        if (!"0".equals(code)) {
            throw new RestClientException("OKX " + code + " for " + instrument + ": " + root.path("msg").asText());
        }
        return root.path("data");
    }

    /** {@code BTCUSDT} → {@code BTC-USDT}. The admin screen only accepts [A-Z0-9], so the split is the quote suffix. */
    static String instrumentId(String symbol) {
        for (String quote : QUOTES) {
            if (symbol.endsWith(quote) && symbol.length() > quote.length()) {
                return symbol.substring(0, symbol.length() - quote.length()) + "-" + quote;
            }
        }
        throw new IllegalArgumentException("Không đổi được cặp " + symbol + " sang mã của OKX.");
    }

    /**
     * {@code 1h} → {@code 1H}. From six hours up OKX aligns plain bars to Hong Kong time and needs a
     * {@code utc} suffix to match the epoch-aligned periods {@link Timeframes} assumes.
     */
    static String bar(String timeframe) {
        char unit = timeframe.charAt(timeframe.length() - 1);
        long value = Long.parseLong(timeframe.substring(0, timeframe.length() - 1));
        return switch (unit) {
            case 'm' -> value + "m";
            case 'h' -> value + (value >= 6 ? "Hutc" : "H");
            case 'd' -> value + "Dutc";
            default -> throw new IllegalArgumentException("Unsupported timeframe: " + timeframe);
        };
    }

    private static void pause() {
        try {
            Thread.sleep(PAGE_PAUSE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RestClientException("Interrupted while paging OKX candles", e);
        }
    }
}

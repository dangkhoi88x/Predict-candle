package com.example.candles.client;

import java.time.Instant;
import java.util.List;

public interface PriceDataProvider {

    /**
     * Fetches candles for a symbol/timeframe within [from, to). Callers are responsible for
     * paging through results if the range exceeds the provider's per-request limit.
     */
    List<CandleData> fetchCandles(String symbol, String timeframe, Instant from, Instant to);
}

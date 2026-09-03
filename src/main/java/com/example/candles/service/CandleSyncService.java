package com.example.candles.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;

import com.example.candles.client.CandleData;
import com.example.candles.client.PriceDataProvider;
import com.example.candles.client.Timeframes;
import com.example.candles.config.CandlesProperties;
import com.example.candles.entity.Asset;
import com.example.candles.entity.Candle;
import com.example.candles.repository.CandleRepository;

/**
 * Fetches any candles newer than what's stored locally. Used both for the initial backfill
 * (when there's no data yet, it fetches from the configured start) and the hourly refresh.
 */
@Service
public class CandleSyncService {

    private static final Logger log = LoggerFactory.getLogger(CandleSyncService.class);

    private final PriceDataProvider priceDataProvider;
    private final CandleRepository candleRepository;
    private final CandlesProperties properties;

    public CandleSyncService(PriceDataProvider priceDataProvider,
                              CandleRepository candleRepository,
                              CandlesProperties properties) {
        this.priceDataProvider = priceDataProvider;
        this.candleRepository = candleRepository;
        this.properties = properties;
    }

    public void sync(Asset asset) {
        String timeframe = properties.timeframe();
        Instant from = candleRepository.findTopByAssetAndTimeframeOrderByOpenTimeDesc(asset, timeframe)
                .map(c -> c.getOpenTime().plusSeconds(1))
                .orElse(properties.backfill().start());
        /*
         * Stop at the last closed candle. The one covering "now" still has its high, low and
         * close moving, and storing it freezes those partial values for good: the next sync
         * starts from the newest stored open time, so a candle written early is never fetched
         * again to be corrected. Ending a millisecond before the current period opens keeps
         * it out regardless of whether a provider treats its end bound as inclusive.
         */
        Instant to = Timeframes.currentPeriodStart(Instant.now(), timeframe).minusMillis(1);

        if (!from.isBefore(to)) {
            return;
        }

        List<CandleData> fetched = priceDataProvider.fetchCandles(asset.getSymbol(), timeframe, from, to);
        if (fetched.isEmpty()) {
            return;
        }

        List<Candle> candles = fetched.stream()
                .map(c -> new Candle(asset, timeframe, c.openTime(), c.open(), c.high(), c.low(), c.close(), c.volume()))
                .toList();
        candleRepository.saveAll(candles);

        log.info("Synced {} candles for {} {} ({} -> {})", candles.size(), asset.getSymbol(), timeframe, from, to);
    }
}

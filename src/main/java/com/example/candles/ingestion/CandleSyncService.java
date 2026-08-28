package com.example.candles.ingestion;

import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.Asset;
import com.example.candles.domain.Candle;
import com.example.candles.provider.CandleData;
import com.example.candles.provider.PriceDataProvider;
import com.example.candles.repository.CandleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

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
        Instant to = Instant.now();

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

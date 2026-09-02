package com.example.candles.ingestion;

import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.Asset;
import com.example.candles.domain.AssetType;
import com.example.candles.repository.AssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Ensures the configured assets exist and keeps their candle history up to date: once at
 * startup (which also performs the initial backfill, since sync() fetches from scratch when
 * no candles exist yet) and then every hour.
 */
@Component
public class CandleSyncScheduler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CandleSyncScheduler.class);

    private final AssetRepository assetRepository;
    private final CandleSyncService candleSyncService;
    private final CandlesProperties properties;

    public CandleSyncScheduler(AssetRepository assetRepository,
                                CandleSyncService candleSyncService,
                                CandlesProperties properties) {
        this.assetRepository = assetRepository;
        this.candleSyncService = candleSyncService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        syncAll();
    }

    @Scheduled(cron = "0 5 * * * *")
    public void scheduledSync() {
        syncAll();
    }

    private void syncAll() {
        ensureAssets();
        /*
         * Seeded from configuration, driven by the database. A pair an admin disabled should
         * stop being fetched — it is off the menu — and one added from the admin screen has no
         * entry in candles.assets to be found under.
         */
        for (Asset asset : assetRepository.findByEnabledTrueOrderByPositionAscSymbolAsc()) {
            try {
                candleSyncService.sync(asset);
            } catch (Exception e) {
                log.error("Failed to sync candles for {}", asset.getSymbol(), e);
            }
        }
    }

    /** Keeps the configured pairs present. Never removes: taking one off the menu is the enabled flag's job. */
    private List<Asset> ensureAssets() {
        return properties.assets().stream()
                .map(config -> assetRepository.findBySymbol(config.symbol())
                        .orElseGet(() -> assetRepository.save(
                                new Asset(config.symbol(), config.name(), AssetType.CRYPTO))))
                .toList();
    }
}

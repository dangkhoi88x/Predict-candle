package com.example.candles.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

import com.example.candles.config.CandlesProperties;
import com.example.candles.entity.Asset;
import com.example.candles.entity.AssetType;
import com.example.candles.repository.AssetRepository;

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

    /**
     * Keeps the configured pairs present. Never removes: taking one off the menu is the enabled
     * flag's job.
     *
     * A pair created here takes its position from where it sits in {@code candles.assets},
     * because that list is the intended order of the picker and nothing else records it. V10
     * wrote those positions into existing rows, but migrations run before this does — against a
     * database that has never booted, its UPDATE matches nothing and every pair would land on
     * the column default. They would then all tie, the order would fall back to the alphabet,
     * and BNB would lead the picker again, which is the exact thing V10 exists to prevent.
     *
     * Only on create. A restart must not undo an order an admin arranged by hand.
     */
    List<Asset> ensureAssets() {
        List<CandlesProperties.AssetConfig> configured = properties.assets();
        List<Asset> assets = new ArrayList<>(configured.size());
        for (int i = 0; i < configured.size(); i++) {
            CandlesProperties.AssetConfig config = configured.get(i);
            int position = i;
            assets.add(assetRepository.findBySymbol(config.symbol()).orElseGet(() -> {
                Asset asset = new Asset(config.symbol(), config.name(), AssetType.CRYPTO);
                asset.setPosition(position);
                return assetRepository.save(asset);
            }));
        }
        return assets;
    }
}

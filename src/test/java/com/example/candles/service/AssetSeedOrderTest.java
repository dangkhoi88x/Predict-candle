package com.example.candles.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

import com.example.candles.config.CandlesProperties;
import com.example.candles.entity.Asset;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.CandleRepository;
import com.example.candles.repository.DemoTradeRepository;
import com.example.candles.repository.GuessResultRepository;
import com.example.candles.repository.LivePredictionRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The picker's order on a database that has never booted before.
 *
 * This only goes wrong on a first run, which is why it went unnoticed: a developer machine has
 * assets that predate V10, so V10's UPDATE found them and set their positions. A fresh database
 * runs every migration against an empty table — the UPDATE matches nothing — and only then does
 * the scheduler insert the pairs. Land them all on the column default and they tie, the order
 * falls back to `symbol ASC`, and BNBUSDT leads the picker. That is precisely the arrangement
 * V10 was written to replace.
 *
 * Emptying the table is the only way to reproduce it, so the test does exactly that and lets
 * the transaction roll it back.
 */
@SpringBootTest
@Transactional
class AssetSeedOrderTest {

    @Autowired private CandleSyncScheduler scheduler;
    @Autowired private AssetRepository assets;
    @Autowired private CandleRepository candles;
    @Autowired private GuessResultRepository guessResults;
    @Autowired private LivePredictionRepository livePredictions;
    @Autowired private DemoTradeRepository demoTrades;
    @Autowired private CandlesProperties properties;

    @Test
    void aFirstRunSeedsThePairsInConfiguredOrderRatherThanAlphabetically() {
        // Children first: everything that points at an asset has to go before the assets do.
        // This list is the one thing here that ages — a new table with an asset_id needs a line,
        // and the failure it causes otherwise depends on whether an earlier test in the same run
        // happened to leave a row behind, so it does not reproduce when the class runs alone.
        demoTrades.deleteAllInBatch();
        livePredictions.deleteAllInBatch();
        guessResults.deleteAllInBatch();
        candles.deleteAllInBatch();
        assets.deleteAllInBatch();
        assets.flush();

        scheduler.ensureAssets();

        List<String> configured = properties.assets().stream()
                .map(CandlesProperties.AssetConfig::symbol).toList();
        List<String> seeded = assets.findAllByOrderByPositionAscSymbolAsc().stream()
                .map(Asset::getSymbol).toList();

        assertThat(seeded).containsExactlyElementsOf(configured);

        // Distinct positions are what stops the tie that let the alphabet decide.
        assertThat(assets.findAll().stream().map(Asset::getPosition).distinct().count())
                .isEqualTo(configured.size());
    }

    @Test
    void aSecondRunLeavesAnOrderAnAdminArrangedAlone() {
        scheduler.ensureAssets();
        List<Asset> before = assets.findAllByOrderByPositionAscSymbolAsc();

        // Push the leader to the back, the way the admin's move buttons would.
        Asset first = before.getFirst();
        first.setPosition(999);
        assets.saveAndFlush(first);

        scheduler.ensureAssets();

        assertThat(assets.findAllByOrderByPositionAscSymbolAsc().getLast().getSymbol())
                .isEqualTo(first.getSymbol());
    }
}

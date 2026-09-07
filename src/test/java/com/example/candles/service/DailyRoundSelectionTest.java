package com.example.candles.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.RoundSelection;
import com.example.candles.entity.Asset;
import com.example.candles.entity.Candle;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.CandleRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The daily chart has one job: everybody who plays on a given UTC day plays the same chart.
 * Nothing stores it, so "the same" has to survive being asked twice, being asked from a second
 * server with its own caches, and being asked either side of an hourly candle sync.
 *
 * That last one is the reason this test class exists. Seeding the draw is the obvious half of
 * determinism and the easy half; the half that bites is that the draw's *range* used to be the
 * live candle count, which grows all day. A seeded draw against a range that moved would have
 * passed every test written at one instant and handed two players an hour apart two different
 * charts.
 */
@SpringBootTest
@Transactional
class DailyRoundSelectionTest {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 15);

    @Autowired private RoundSelectionService rounds;
    @Autowired private AssetRepository assets;
    @Autowired private CandleRepository candles;
    @Autowired private CandlesProperties properties;

    private static String describe(RoundSelection round) {
        return round.asset().getSymbol() + "@" + round.startIndex();
    }

    @Test
    void theSameDayGivesTheSameChartWhenAskedAgain() {
        // Asked twice of one service, which is also where the repeat cache would interfere if
        // it were being honoured: a practice round would deliberately not repeat here.
        assertThat(describe(rounds.selectDailyRound(DAY)))
                .isEqualTo(describe(rounds.selectDailyRound(DAY)));
    }

    @Test
    void aSecondServerWithItsOwnCachesAgrees() {
        RoundSelectionService otherServer = new RoundSelectionService(assets, candles, properties);

        assertThat(describe(otherServer.selectDailyRound(DAY)))
                .isEqualTo(describe(rounds.selectDailyRound(DAY)));
    }

    @Test
    void anHourlySyncDoesNotMoveTodaysChart() {
        RoundSelection before = rounds.selectDailyRound(DAY);

        appendCandleTo(before.asset());

        // The new candle closed after the day began, so the history the day draws from is
        // untouched — which is the entire reason the count is taken as of midnight.
        assertThat(describe(rounds.selectDailyRound(DAY))).isEqualTo(describe(before));
    }

    @Test
    void differentDaysGiveDifferentCharts() {
        Set<String> charts = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            charts.add(describe(rounds.selectDailyRound(DAY.plusDays(i))));
        }
        // Not 30 — two days landing on the same window is a coincidence, not a bug — but a
        // month collapsing to a handful would mean the seed is barely reaching the draw.
        assertThat(charts).hasSizeGreaterThan(25);
    }

    @Test
    void theChartIsPlayableLikeAnyOther() {
        RoundSelection round = rounds.selectDailyRound(DAY);

        assertThat(round.visibleCandles()).hasSize(properties.round().visibleCandles());
        assertThat(round.timeframe()).isEqualTo(properties.timeframe());
        assertThat(round.startIndex()).isNotNegative();
    }

    /** One more candle at the end of an asset's history, the way the hourly sync adds them. */
    private void appendCandleTo(Asset asset) {
        List<Candle> last = candles.findWindow(asset.getId(), properties.timeframe(),
                (int) candles.countByAssetAndTimeframe(asset, properties.timeframe()) - 1, 1);
        Instant next = last.get(0).getOpenTime().plus(Duration.ofHours(1));
        candles.saveAndFlush(new Candle(asset, properties.timeframe(), next,
                BigDecimal.valueOf(100), BigDecimal.valueOf(110),
                BigDecimal.valueOf(90), BigDecimal.valueOf(105), BigDecimal.ONE));
    }
}

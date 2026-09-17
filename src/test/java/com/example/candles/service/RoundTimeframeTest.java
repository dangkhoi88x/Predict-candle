package com.example.candles.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.example.candles.CandleFixture;
import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.RoundSelection;
import com.example.candles.entity.Asset;
import com.example.candles.entity.AssetType;
import com.example.candles.entity.Candle;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.CandleRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Playing practice on a longer chart, folded from the hourly candles already stored.
 *
 * The whole feature is arithmetic on one coordinate space: an index is still a position among the
 * stored rows, and a bar is still the clock period an exchange would draw. Both are the parts that
 * fail silently — a bar that starts an hour off looks like a chart, and an index read as bars
 * rather than hours picks a different chart every time — so they are what this pins.
 */
@SpringBootTest
@Transactional
class RoundTimeframeTest {

    @Autowired private RoundCandleService roundCandles;
    @Autowired private RoundSelectionService rounds;
    @Autowired private AssetRepository assets;
    @Autowired private CandleRepository candles;
    @Autowired private CandlesProperties properties;

    private Asset pair;

    @BeforeEach
    void seedHistory() {
        pair = assets.saveAndFlush(new Asset(
                "TF" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(), "Timeframe pair", AssetType.CRYPTO));
        CandleFixture.seedIfEmpty(candles, pair, properties.timeframe());
    }

    @Test
    void onlyTheOfferedTimeframesArePlayable() {
        assertThat(roundCandles.playable()).startsWith(properties.timeframe());
        assertThat(roundCandles.resolve(null)).isEqualTo(properties.timeframe());
        assertThat(roundCandles.resolve("4H")).isEqualTo("4h");

        // Below the stored timeframe there is nothing to fold: those minutes were never recorded.
        assertThatThrownBy(() -> roundCandles.resolve("15m")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> roundCandles.resolve("3h")).isInstanceOf(IllegalArgumentException.class);
        assertThat(roundCandles.storedPerBar("1h")).isEqualTo(1);
        assertThat(roundCandles.storedPerBar("4h")).isEqualTo(4);
        assertThat(roundCandles.storedPerBar("1d")).isEqualTo(24);
    }

    /** A bar is the exchange's period, and its four hours folded — not four hours from wherever the draw landed. */
    @Test
    void aFourHourBarCoversItsOwnClockPeriodAndFoldsTheStoredHoursInIt() {
        RoundSelection round = rounds.selectRound(pair.getSymbol(), "4h");
        assertThat(round.timeframe()).isEqualTo("4h");
        assertThat(round.visibleCandles()).hasSize(properties.round().visibleCandles());

        Candle first = round.visibleCandles().getFirst();
        assertThat(first.getOpenTime().toEpochMilli() % Duration.ofHours(4).toMillis())
                .as("bars are aligned to the clock, not to the window")
                .isZero();
        assertThat(Duration.between(first.getOpenTime(), round.visibleCandles().get(1).getOpenTime()))
                .isEqualTo(Duration.ofHours(4));

        List<Candle> stored = candles.findWindow(pair.getId(), properties.timeframe(), round.startIndex(), 4);
        assertThat(stored).hasSize(4);
        assertThat(first.getOpen()).isEqualByComparingTo(stored.getFirst().getOpen());
        assertThat(first.getClose()).isEqualByComparingTo(stored.getLast().getClose());
        assertThat(first.getHigh()).isEqualByComparingTo(
                stored.stream().map(Candle::getHigh).max(BigDecimal::compareTo).orElseThrow());
        assertThat(first.getLow()).isEqualByComparingTo(
                stored.stream().map(Candle::getLow).min(BigDecimal::compareTo).orElseThrow());
        assertThat(first.getVolume()).isEqualByComparingTo(
                stored.stream().map(Candle::getVolume).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /**
     * The answer is the next bar, not the next hour. An index read as bars rather than as stored
     * rows would quietly ask about a candle five hours earlier than the one on screen.
     */
    @Test
    void theAnswerOfALongerRoundIsTheBarAfterTheVisibleOnes() {
        RoundSelection round = rounds.selectRound(pair.getSymbol(), "4h");
        Candle lastVisible = round.visibleCandles().getLast();

        Candle answer = rounds.answerCandleAt(pair, "4h", round.startIndex(), 1);
        assertThat(Duration.between(lastVisible.getOpenTime(), answer.getOpenTime()))
                .isEqualTo(Duration.ofHours(4));

        List<Candle> allAnswers = rounds.answerCandles(pair, "4h", round.startIndex(),
                properties.round().guessesPerChart());
        assertThat(allAnswers).hasSize(properties.round().guessesPerChart());
        assertThat(allAnswers.getFirst().getOpenTime()).isEqualTo(answer.getOpenTime());
        assertThat(Duration.between(allAnswers.getFirst().getOpenTime(), allAnswers.getLast().getOpenTime()))
                .isEqualTo(Duration.ofHours(4L * (properties.round().guessesPerChart() - 1)));
    }

    @Test
    void aDailyBarFoldsAWholeUtcDayAndTheContextChartStaysOnTheSameGrid() {
        RoundSelection round = rounds.selectRound(pair.getSymbol(), "1d");

        Candle first = round.visibleCandles().getFirst();
        assertThat(first.getOpenTime().toEpochMilli() % Duration.ofDays(1).toMillis()).isZero();
        assertThat(Duration.between(first.getOpenTime(), round.visibleCandles().getLast().getOpenTime()))
                .isEqualTo(Duration.ofDays(properties.round().visibleCandles() - 1L));

        RoundSelectionService.ContextWindow context =
                rounds.contextWindow(pair, "1d", round.startIndex());
        assertThat(context.candles().get(context.leading()).getOpenTime())
                .as("the played window starts exactly where the context says it does")
                .isEqualTo(first.getOpenTime());
        assertThat(Duration.between(context.candles().getFirst().getOpenTime(),
                context.candles().get(1).getOpenTime())).isEqualTo(Duration.ofDays(1));
    }

    /** The stored timeframe keeps reading rows straight out of the table, with no folding at all. */
    @Test
    void theStoredTimeframeIsUntouched() {
        RoundSelection round = rounds.selectRound(pair.getSymbol(), properties.timeframe());
        List<Candle> stored = candles.findWindow(pair.getId(), properties.timeframe(),
                round.startIndex(), properties.round().visibleCandles());

        assertThat(round.visibleCandles()).extracting(Candle::getOpenTime)
                .isEqualTo(stored.stream().map(Candle::getOpenTime).toList());
        assertThat(rounds.selectRound(pair.getSymbol()).timeframe()).isEqualTo(properties.timeframe());
    }
}

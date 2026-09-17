package com.example.candles.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.PlayerInsights;
import com.example.candles.dto.response.InsightsResponse;
import com.example.candles.entity.Asset;
import com.example.candles.entity.Candle;
import com.example.candles.entity.GuessResult;
import com.example.candles.exception.InvalidCredentialsException;
import com.example.candles.repository.CandleRepository;
import com.example.candles.repository.GuessResultRepository;
import com.example.candles.repository.UserRepository;

/**
 * Reads a player's recent calls back together with the candles each one was made on, and hands
 * them to {@link PlayerInsights}.
 *
 * <b>Only the last {@link #WINDOW} calls.</b> Two reasons, and the first is not performance: a
 * habit is about how somebody plays now, and a thousand calls from their first week would drown out
 * the correction they have since made. The second is that the cost is then bounded — one query for
 * the calls and one per pair for the candles, whatever the account's history.
 *
 * <b>Candles are addressed by index, the same index a round token carries.</b> A guess row stores
 * the chart's {@code start_index} and its {@code guess_number}; the last candle the player could see
 * is {@code start + visible + guess - 2}. {@link CandleRepository#candlesAtIndexes} numbers candles by
 * {@code open_time} exactly as {@link CandleRepository#findWindow}'s OFFSET does, which is the only
 * thing that makes those two agree — {@code InsightsFlowTest} pins it against {@code findWindow}.
 */
@Service
public class InsightsService {

    static final int WINDOW = 500;

    /** A habit is a person's evening, and the people playing this are in Vietnam. */
    static final ZoneId PLAYER_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final GuessResultRepository guessResults;
    private final CandleRepository candles;
    private final UserRepository users;
    private final CandlesProperties properties;
    private final RoundPatternScanner patternScanner;

    public InsightsService(GuessResultRepository guessResults, CandleRepository candles,
                           UserRepository users, CandlesProperties properties,
                           RoundPatternScanner patternScanner) {
        this.guessResults = guessResults;
        this.candles = candles;
        this.users = users;
        this.properties = properties;
        this.patternScanner = patternScanner;
    }

    @Transactional(readOnly = true)
    public InsightsResponse forUser(Long userId) {
        // Same answer StatsService gives a session naming a deleted account: invalid, not a 500.
        if (!users.existsById(userId)) throw new InvalidCredentialsException();

        List<GuessResult> recent = guessResults.findRecent(userId, PageRequest.of(0, WINDOW));
        Map<ChartKey, Map<Long, Candle>> trailing = trailingCandles(recent);

        List<PlayerInsights.Observation> observations = new ArrayList<>(recent.size());
        for (GuessResult guess : recent) {
            List<Candle> seen = lastSeen(guess, trailing.get(ChartKey.of(guess)));
            observations.add(new PlayerInsights.Observation(guess.getGuessedDirection(),
                    guess.getActualDirection(), PlayerInsights.trendOf(seen), patternsOnLastCandle(seen),
                    guess.getCreatedAt()));
        }
        return toResponse(PlayerInsights.fold(observations, PLAYER_ZONE));
    }

    /**
     * Patterns completing on the last candle the player could see — the same scan that names the
     * patterns in a finished round, pointed at the one candle a call was made after. The library's
     * longest pattern is three candles and five are loaded, so every pattern has room.
     */
    private List<String> patternsOnLastCandle(List<Candle> seen) {
        if (seen.isEmpty()) return List.of();
        return patternScanner.scan(seen, seen.size() - 1, seen.size()).stream()
                .map(RoundPatternScanner.PatternHit::patternId)
                .distinct()
                .toList();
    }

    /** Index of the last candle a player could see when making {@code guess}. */
    int lastVisibleIndex(GuessResult guess) {
        return guess.getStartIndex() + properties.round().visibleCandles() + guess.getGuessNumber() - 2;
    }

    private List<Candle> lastSeen(GuessResult guess, Map<Long, Candle> byIndex) {
        List<Candle> seen = new ArrayList<>(PlayerInsights.TREND_CANDLES);
        if (byIndex == null) return seen;
        long last = lastVisibleIndex(guess);
        for (long i = last - PlayerInsights.TREND_CANDLES + 1; i <= last; i++) {
            Candle c = byIndex.get(i);
            if (c == null) return List.of(); // a gap reads as "cannot judge", never as a shorter window
            seen.add(c);
        }
        return seen;
    }

    /**
     * Only calls made on the stored timeframe are read back: a 4h round's candles are folded from
     * the hours rather than stored, so there are no rows to address by index. Those calls still
     * count everywhere a candle is not needed — the long/short split, the sessions, the timeouts —
     * and simply have no trend and no patterns, the same as a call whose candles have a gap in them.
     */
    private Map<ChartKey, Map<Long, Candle>> trailingCandles(List<GuessResult> recent) {
        Map<ChartKey, TreeSet<Long>> wanted = new HashMap<>();
        Map<ChartKey, Asset> assets = new HashMap<>();
        for (GuessResult guess : recent) {
            if (!properties.timeframe().equals(guess.getTimeframe())) continue;
            ChartKey key = ChartKey.of(guess);
            assets.putIfAbsent(key, guess.getAsset());
            long last = lastVisibleIndex(guess);
            TreeSet<Long> indexes = wanted.computeIfAbsent(key, k -> new TreeSet<>());
            for (long i = Math.max(0, last - PlayerInsights.TREND_CANDLES + 1); i <= last; i++) indexes.add(i);
        }

        Map<ChartKey, Map<Long, Candle>> out = new HashMap<>();
        wanted.forEach((key, indexes) -> {
            Map<Long, Candle> byIndex = new HashMap<>();
            for (Object[] row : candles.candlesAtIndexes(key.assetId(), key.timeframe(), indexes)) {
                byIndex.put(((Number) row[0]).longValue(), new Candle(assets.get(key), key.timeframe(),
                        Instant.ofEpochMilli(((Number) row[1]).longValue()),
                        (BigDecimal) row[2], (BigDecimal) row[3], (BigDecimal) row[4], (BigDecimal) row[5],
                        (BigDecimal) row[6]));
            }
            out.put(key, byIndex);
        });
        return out;
    }

    private InsightsResponse toResponse(PlayerInsights.Summary s) {
        PlayerInsights.Calls c = s.calls();
        return new InsightsResponse(
                s.analysed(), WINDOW, PlayerInsights.MIN_SAMPLE, s.enough(), s.timedOut(),
                new InsightsResponse.Calls(c.longCalls(), c.shortCalls(), c.marketUp(), c.marketDown(),
                        c.correctLong(), c.correctShort(), c.correctWhenUp(), c.correctWhenDown()),
                s.trends().entrySet().stream()
                        .map(e -> new InsightsResponse.TrendBucket(e.getKey().name(), e.getValue().total(),
                                e.getValue().correct(), e.getValue().longCalls()))
                        .toList(),
                s.sessions().entrySet().stream()
                        .map(e -> new InsightsResponse.SessionBucket(e.getKey().name(), e.getValue().total(),
                                e.getValue().correct(), e.getValue().longCalls()))
                        .toList(),
                s.patterns().entrySet().stream()
                        .map(e -> new InsightsResponse.PatternBucket(e.getKey(), e.getValue().total(),
                                e.getValue().correct(), e.getValue().longCalls()))
                        .toList(),
                s.findings().stream()
                        .map(f -> new InsightsResponse.Finding(f.kind().name(), f.key(), f.gapPoints()))
                        .toList());
    }

    private record ChartKey(Long assetId, String timeframe) {
        static ChartKey of(GuessResult guess) {
            return new ChartKey(guess.getAsset().getId(), guess.getTimeframe());
        }
    }
}

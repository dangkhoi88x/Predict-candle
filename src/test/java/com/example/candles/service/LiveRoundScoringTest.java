package com.example.candles.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.example.candles.domain.PlayerScore;
import com.example.candles.dto.response.Leaderboard;
import com.example.candles.dto.response.StatsResponse;
import com.example.candles.entity.Asset;
import com.example.candles.entity.AssetType;
import com.example.candles.entity.Candle;
import com.example.candles.entity.Direction;
import com.example.candles.entity.GuessResult;
import com.example.candles.entity.LivePrediction;
import com.example.candles.entity.User;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.CandleRepository;
import com.example.candles.repository.GuessResultRepository;
import com.example.candles.repository.LivePredictionRepository;
import com.example.candles.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live-round calls used to be invisible to score: {@code live_predictions} had no settlement
 * and neither {@link PlayerScore} nor {@link LeaderboardService} ever read it, so a player could
 * call rounds all day and their score, streak and rank would never move. These tests pin the
 * fix — one player, one score, whichever tab a call was made from — at the three places that
 * matter: the repository join that reads a call's outcome, the leaderboard that ranks on it,
 * and the profile that reports it back to the player.
 */
@SpringBootTest
@Transactional
class LiveRoundScoringTest {

    @Autowired private UserRepository users;
    @Autowired private AssetRepository assets;
    @Autowired private GuessResultRepository guessResults;
    @Autowired private LivePredictionRepository livePredictions;
    @Autowired private CandleRepository candles;
    @Autowired private LeaderboardService leaderboard;
    @Autowired private StatsService stats;

    private Asset asset;

    @BeforeEach
    void freshCacheAndAsset() {
        leaderboard.evict();
        asset = assets.saveAndFlush(
                new Asset("TEST" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(), "Test pair", AssetType.CRYPTO));
    }

    private User player(String name) {
        return users.saveAndFlush(new User("0x" + UUID.randomUUID().toString().replace("-", ""), name));
    }

    /** (user_id, asset_id, timeframe, start_index, guess_number) is unique, so each practice
        guess in a test needs its own start_index — this counter hands out one per call. */
    private int nextStartIndex = 0;

    private void practiceGuess(User user, boolean correct) {
        guessResults.saveAndFlush(new GuessResult(user, asset, "1h", nextStartIndex++, 1,
                Direction.LONG, correct ? Direction.LONG : Direction.SHORT));
    }

    /** A settled live call: the candle for {@code openTime} exists, so the round has an outcome. */
    private void settledLiveCall(User user, Instant openTime, boolean correct) {
        candles.saveAndFlush(new Candle(asset, "1h", openTime,
                new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("90"),
                correct ? new BigDecimal("105") : new BigDecimal("95"), new BigDecimal("10")));
        livePredictions.saveAndFlush(new LivePrediction(user, asset, "1h", openTime, Direction.LONG));
    }

    /** An open live call: no candle yet, so the round has not settled. */
    private void openLiveCall(User user, Instant openTime) {
        livePredictions.saveAndFlush(new LivePrediction(user, asset, "1h", openTime, Direction.LONG));
    }

    @Test
    void settledLiveCallsJoinTheFlagsAndAnOpenOneIsInvisible() {
        User user = player("mixed-" + UUID.randomUUID());
        Instant base = Instant.now().minus(1, ChronoUnit.DAYS);

        practiceGuess(user, true);
        practiceGuess(user, true);
        settledLiveCall(user, base, true);
        settledLiveCall(user, base.plusSeconds(3600), false);
        openLiveCall(user, base.plusSeconds(7200)); // still forming — must not appear at all
        practiceGuess(user, false);

        List<Boolean> flags = livePredictions.combinedResultFlagsInPlayOrder(user.getId());

        // 2 correct + 1 wrong practice, 1 correct + 1 wrong settled live — the open call is absent,
        // not just uncounted, or it would silently affect ordering once it eventually did settle.
        assertThat(flags).hasSize(5);
        assertThat(flags.stream().filter(Boolean::booleanValue).count()).isEqualTo(3);
    }

    @Test
    void liveCallsCountTowardTheLeaderboardAndItsScore() {
        String name = "live-heavy-" + UUID.randomUUID();
        User user = player(name);
        Instant base = Instant.now().minus(30, ChronoUnit.DAYS);

        // Below MIN_GUESSES on practice alone — this player would not qualify without live rounds.
        for (int i = 0; i < 5; i++) practiceGuess(user, true);
        List<Boolean> allCorrect = new ArrayList<>();
        Collections.addAll(allCorrect, true, true, true, true, true);
        for (int i = 0; i < 20; i++) {
            settledLiveCall(user, base.plusSeconds(3600L * i), true);
            allCorrect.add(true);
        }
        leaderboard.evict();

        Leaderboard board = leaderboard.board(50, user.getId());
        Leaderboard.Row row = board.rows().stream()
                .filter(r -> r.displayName().equals(name))
                .findFirst().orElse(board.me());

        assertThat(row).isNotNull();
        assertThat(row.total()).isEqualTo(25);
        assertThat(row.correct()).isEqualTo(25);
        // Every flag is true regardless of interleave order, so this is one unbroken streak —
        // the exact score PlayerScore assigns to 25 straight correct calls.
        assertThat(row.score()).isEqualTo(PlayerScore.of(allCorrect).score());
    }

    @Test
    void theProfileTotalsIncludeSettledLiveCalls() {
        User user = player("profile-" + UUID.randomUUID());
        Instant base = Instant.now().minus(2, ChronoUnit.DAYS);

        practiceGuess(user, true);
        practiceGuess(user, true);
        practiceGuess(user, false);
        settledLiveCall(user, base, true);
        settledLiveCall(user, base.plusSeconds(3600), true);
        openLiveCall(user, base.plusSeconds(7200));

        StatsResponse response = stats.forUser(user.getId());

        assertThat(response.total()).isEqualTo(5);
        assertThat(response.correct()).isEqualTo(4);
        assertThat(response.recorded().total()).isEqualTo(5);
        assertThat(response.recorded().correct()).isEqualTo(4);
    }
}

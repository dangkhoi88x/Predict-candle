package com.example.candles.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

import com.example.candles.domain.PlayerScore;
import com.example.candles.dto.LegacyStatsRequest;
import com.example.candles.dto.StatsResponse;
import com.example.candles.entity.User;
import com.example.candles.repository.GuessResultRepository;
import com.example.candles.repository.UserRepository;

@Service
public class StatsService {

    /** Enough to show a run of form without turning the profile into an audit log. */
    private static final int RECENT_LIMIT = 20;

    /**
     * The most a single correct guess can be worth, used to sanity-check imported totals.
     * Kept in step with PlayerScore's own constants.
     */
    private static final long MAX_POINTS_PER_GUESS = 30;

    /** Far past any plausible history, low enough that a fabricated number stands out. */
    private static final long MAX_LEGACY_GUESSES = 100_000;

    private final GuessResultRepository guessResultRepository;
    private final UserRepository userRepository;

    public StatsService(GuessResultRepository guessResultRepository, UserRepository userRepository) {
        this.guessResultRepository = guessResultRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public StatsResponse forUser(Long userId) {
        // Totals come from the same walk as the streak rather than a separate count query, so
        // there is no window in which the two could disagree.
        PlayerScore recorded = PlayerScore.of(guessResultRepository.resultFlagsInPlayOrder(userId));

        List<StatsResponse.AssetTally> byAsset = guessResultRepository.tallyByAsset(userId).stream()
                .map(row -> new StatsResponse.AssetTally(
                        (String) row[0],
                        ((Number) row[1]).longValue(),
                        ((Number) row[2]).longValue()))
                .toList();

        User user = userRepository.findById(userId).orElseThrow();

        // Streaks either side of the carry-over cannot be joined into one run — nothing
        // records whether the last pre-import guess and the first recorded one were even
        // consecutive — so the longer of the two is the honest answer.
        int bestStreak = Math.max(recorded.bestStreak(), user.getLegacyBestStreak());

        List<StatsResponse.RecentGuess> recent = guessResultRepository
                .findRecent(userId, PageRequest.of(0, RECENT_LIMIT)).stream()
                .map(g -> new StatsResponse.RecentGuess(
                        g.getAsset().getSymbol(),
                        // Null when the countdown expired before an answer. V3 made the column
                        // nullable precisely so "no answer" and "wrong answer" stay different
                        // rows; calling name() on it took the whole profile down with a 500 the
                        // moment a player let one clock run out.
                        g.getGuessedDirection() == null ? null : g.getGuessedDirection().name(),
                        g.getActualDirection().name(),
                        g.isCorrect(),
                        g.getCreatedAt()))
                .toList();

        return new StatsResponse(
                recorded.total() + user.getLegacyTotal(),
                recorded.correct() + user.getLegacyCorrect(),
                bestStreak,
                // The run in progress is always the recorded one — an imported tally has no
                // "now" to continue from.
                recorded.currentStreak(),
                recorded.score() + user.getLegacyScore(),
                new StatsResponse.Recorded(recorded.total(), recorded.correct(),
                        recorded.bestStreak(), recorded.currentStreak(), recorded.score()),
                user.hasImportedLegacyStats(),
                byAsset,
                recent);
    }

    /**
     * Folds a browser's stored tally into the account, once.
     *
     * Everything here is asserted by the client, so the checks are about coherence rather
     * than truth: a set of numbers that cannot have come from actually playing is rejected.
     * That does not make an inflated-but-plausible claim impossible, which is why the
     * response keeps recorded totals separate for anything that ranks players.
     */
    @Transactional
    public StatsResponse importLegacy(Long userId, LegacyStatsRequest request) {
        User user = userRepository.findById(userId).orElseThrow();

        if (!user.hasImportedLegacyStats() && isCoherent(request)) {
            user.importLegacyStats(request.total(), request.correct(), request.score(), request.bestStreak());
            userRepository.save(user);
        }
        // Either way the caller gets the current picture, so a repeated call is a no-op
        // rather than an error the client has to handle.
        return forUser(userId);
    }

    private boolean isCoherent(LegacyStatsRequest r) {
        return r.total() <= MAX_LEGACY_GUESSES
                && r.correct() <= r.total()
                && r.bestStreak() <= r.correct()
                && r.score() <= r.correct() * MAX_POINTS_PER_GUESS;
    }
}

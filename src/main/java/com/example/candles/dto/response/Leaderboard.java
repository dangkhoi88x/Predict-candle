package com.example.candles.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * The public ranking of signed-in players.
 *
 * Ranked on {@code score}, which is the number this game already shows on the game tab and the
 * profile — 10 a correct guess plus a streak bonus — so the board does not introduce a measure
 * nobody has seen before. Accuracy travels alongside it because score alone cannot separate a
 * sharp player from a persistent one, and a reader can see both at a glance.
 *
 * **Every figure here is server-scored.** The {@code legacy_*} columns on a user — the browser
 * tally folded in at first sign-in — are deliberately excluded: they arrive from the client and
 * only get a coherence check, so a board that counted them would rank whoever was willing to
 * post the largest believable number. Profiles still show the combined total; ranking does not.
 */
public record Leaderboard(
        Instant generatedAt,
        int minGuesses,
        List<Row> rows,
        /** Where the caller sits, even when that is outside the returned page. Null if signed
            out, or short of {@code minGuesses}. */
        Row me
) {

    public record Row(int rank, String displayName, long score, long total, long correct,
                       Double accuracy, int bestStreak) {
    }
}

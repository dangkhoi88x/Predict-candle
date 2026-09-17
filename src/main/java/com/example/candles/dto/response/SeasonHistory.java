package com.example.candles.dto.response;

import java.util.List;

/**
 * Finished seasons: who took each month, and which of them the caller is on.
 *
 * A "season badge" is not a row written when a month ends — the same bargain {@code Achievement}
 * makes. It is a question asked of the same guesses the board already ranks, so it cannot drift
 * from them, a month is never awarded twice, and deleting an account takes its medals with it.
 * The cost is the same one badges pay: there is no *when* beyond the month itself.
 *
 * @param seasons newest first, and only months somebody qualified in — an empty month is not a
 *                season anybody wants listed
 * @param mine    the caller's podium finishes in the same window, newest first; empty when signed
 *                out or never in a top three
 */
public record SeasonHistory(List<Past> seasons, List<Medal> mine) {

    public record Past(String id, String label, List<Leaderboard.Row> podium) {
    }

    public record Medal(String seasonId, String label, int rank) {
    }
}

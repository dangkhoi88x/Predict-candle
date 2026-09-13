package com.example.candles.dto.response;

import java.time.LocalDate;
import java.util.List;

/**
 * What the site is asking, day by day — the daily chart and the pattern quiz side by side.
 *
 * Both are chosen from the date alone and nothing about either is stored, which is what makes
 * them worth previewing: there is no row to inspect and no job whose log says what tomorrow
 * will be. Until now the only way to find out was to wait for midnight and let the players
 * find out with you.
 *
 * The failure both selectors share is the reason this exists. {@code selectDailyRound} throws
 * when no pair has enough history; {@code PatternQuizService} throws when no pattern in the
 * library has a single unambiguous occurrence anywhere. Either one leaves a whole day of the
 * site broken for everybody until the next midnight, and neither says a word in advance.
 */
public record AdminChallenges(LocalDate today, List<Day> days) {

    /**
     * One day.
     *
     * {@code provisional} is the honest half of a preview. A day's chart is drawn from the
     * candles that had closed before *its* midnight, and for a day still to come that count is
     * still rising — so tomorrow's pick will move as the hourly sync lands, exactly as
     * {@code anHourlySyncDoesNotMoveTodaysChart} pins that today's does not. A future row is
     * therefore a rehearsal, not a promise: what it proves is that the day can be built at all,
     * which is the failure worth catching early.
     */
    public record Day(LocalDate day, long roundNumber, boolean provisional, boolean isToday,
                       Daily daily, Quiz quiz, Participation participation) {
    }

    /** {@code problem} is the selector's own message when it refused to pick; then the rest is null. */
    public record Daily(String asset, String timeframe, Integer startIndex, String problem) {
    }

    /**
     * {@code resolved} says where the pattern came from. A future or current day is built by
     * asking the selector; a past day is read off the answers people gave, because rebuilding a
     * question means scanning every asset's whole history against every pattern in the library
     * and the answer is already recorded. A past day nobody answered has neither, and says so
     * rather than paying for a scan to satisfy curiosity.
     */
    public record Quiz(String patternId, String patternName, String asset,
                        List<String> choices, String resolved, String problem) {
    }

    /**
     * How the day went, for days that have happened.
     *
     * The two games are counted apart because they are stored apart: a pattern answer is one of
     * thirteen ids and lives in its own table, invisible to everything that reads
     * {@code guess_results}. Adding them together would be a number about nothing.
     */
    public record Participation(long dailyPlayers, long dailyGuesses, long dailyCorrect,
                                 long quizAnswers, long quizCorrect) {
    }
}

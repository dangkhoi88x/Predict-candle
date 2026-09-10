package com.example.candles.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.example.candles.entity.GuessMode;
import com.example.candles.entity.GuessResult;

public interface GuessResultRepository extends JpaRepository<GuessResult, Long> {

    /**
     * Written out rather than derived from the method name, which would run to well over a
     * hundred characters for these five columns.
     */
    @Query("""
            select count(g) > 0 from GuessResult g
            where g.user.id = :userId
              and g.mode = :mode
              and g.asset.id = :assetId
              and g.timeframe = :timeframe
              and g.startIndex = :startIndex
              and g.guessNumber = :guessNumber
            """)
    boolean alreadyRecorded(@Param("userId") Long userId,
                            @Param("mode") GuessMode mode,
                            @Param("assetId") Long assetId,
                            @Param("timeframe") String timeframe,
                            @Param("startIndex") int startIndex,
                            @Param("guessNumber") int guessNumber);

    /**
     * One player's guesses on one chart in one mode, in the order they were asked — how the
     * daily challenge rebuilds a session the player already finished.
     *
     * There is no "daily attempt" row anywhere; a day's chart is fixed, so these guesses are
     * the attempt. Reading them back is what lets a returning player see their own result
     * instead of being handed the chart again.
     */
    @Query("""
            select g from GuessResult g
            where g.user.id = :userId
              and g.mode = :mode
              and g.asset.id = :assetId
              and g.timeframe = :timeframe
              and g.startIndex = :startIndex
            order by g.guessNumber
            """)
    List<GuessResult> findRoundGuesses(@Param("userId") Long userId,
                                       @Param("mode") GuessMode mode,
                                       @Param("assetId") Long assetId,
                                       @Param("timeframe") String timeframe,
                                       @Param("startIndex") int startIndex);

    /**
     * The distinct UTC days this player played the daily challenge on, newest first — folded by
     * {@link com.example.candles.domain.PlayStreak} into the streak the daily tab shows.
     *
     * Deliberately not the same number as the profile's day streak, which counts any play at
     * all. Turning up to practice does not keep a daily streak alive: this one is the promise
     * that you did today's chart, and it has to be able to break while the other holds.
     */
    @Query(value = """
            select distinct cast(g.created_at at time zone 'UTC' as date) as day
            from guess_results g
            where g.user_id = :userId and g.mode = 'DAILY'
            order by day desc
            """, nativeQuery = true)
    List<LocalDate> distinctDailyDaysDesc(@Param("userId") Long userId);

    /**
     * Just the flags, in play order. Streak length cannot be reached with count/sum, and
     * selecting the column rather than the entity keeps this off the hydration path.
     */
    @Query("select g.correct from GuessResult g where g.user.id = :userId order by g.createdAt")
    List<Boolean> resultFlagsInPlayOrder(@Param("userId") Long userId);

    /** Newest first. Pageable rather than a limit clause, which JPQL has no portable form of. */
    /** Rows of [userId, total, correct, lastPlayed] for the admin player list. */
    @Query(value = "select g.user.id, count(g), coalesce(sum(case when g.correct then 1 else 0 end), 0),"
            + " max(g.createdAt) from GuessResult g group by g.user.id")
    List<Object[]> tallyByUser();

    /**
     * [total, correct, lastPlayed] for one account — the same three figures {@link #tallyByUser}
     * produces for every account at once, asked about one.
     *
     * Worth its own query rather than filtering that one: after a rename, and on the detail
     * view, the caller wants a single row, and reaching it through the grouped query means
     * tallying every account in the database to throw all but one away.
     */
    @Query("""
            select count(g), coalesce(sum(case when g.correct then 1 else 0 end), 0), max(g.createdAt)
            from GuessResult g where g.user.id = :userId
            """)
    Object[] tallyForUser(@Param("userId") Long userId);

    void deleteByUserId(Long userId);

    /** [total, correct] across every player since an instant — the operations panel's activity figures. */
    @Query(value = "select count(g), coalesce(sum(case when g.correct then 1 else 0 end), 0)"
            + " from GuessResult g where g.createdAt >= :since")
    Object[] activitySince(@Param("since") Instant since);

    @Query("select g from GuessResult g join fetch g.asset where g.user.id = :userId order by g.createdAt desc")
    List<GuessResult> findRecent(@Param("userId") Long userId, Pageable pageable);

    /**
     * One player's history split by game, as rows of [mode, total, correct].
     *
     * The three are worth telling apart on an account: a player with hundreds of practice rows
     * and no daily ones is a different person from one with the reverse, and the totals on the
     * list above cannot say which.
     */
    @Query("""
            select g.mode, count(g), sum(case when g.correct then 1 else 0 end)
            from GuessResult g
            where g.user.id = :userId
            group by g.mode
            """)
    List<Object[]> tallyByMode(@Param("userId") Long userId);

    /** Rows of [symbol, total, correct]. */
    @Query("""
            select a.symbol, count(g), sum(case when g.correct then 1 else 0 end)
            from GuessResult g join g.asset a
            where g.user.id = :userId
            group by a.symbol
            order by count(g) desc
            """)
    List<Object[]> tallyByAsset(@Param("userId") Long userId);

    /**
     * Guesses bucketed by calendar unit for the admin overview charts. Rows of
     * [bucketStart, guesses, long, short, correct, activePlayers, practice, daily, archive].
     *
     * Native because JPQL has no portable date truncation, and because the counts want
     * FILTER — four passes with CASE would read worse and run no faster. {@code unit} is not
     * user input: the caller maps a fixed enum onto 'day' / 'week' / 'month' / 'year'.
     *
     * Truncation happens in UTC rather than the session's zone, so the same deploy read from
     * two machines buckets a guess into the same day. It also matches how the operations
     * panel already draws its "today" line.
     *
     * Two totals, because the page needs both and they are not the same number. {@code count(*)}
     * is every guess the player was asked — the denominator the rest of the app scores accuracy
     * on, timed-out guesses included, since PlayerScore counts one of those against you.
     * long + short is the smaller subset that has a direction to stack on the chart. On the
     * current data the two differ by about a fifth, so picking the wrong one is visible.
     *
     * The three mode counts are a third partition of the same rows, and they do add up to
     * {@code count(*)} — every guess came from exactly one of the three games. They are
     * counted here rather than in a query of their own so the split cannot drift from the
     * total it is a split of: one scan, one set of rows, one bucket boundary.
     */
    @Query(value = """
            select date_trunc(cast(:unit as text), g.created_at at time zone 'UTC') as bucket,
                   count(*),
                   count(*) filter (where g.guessed_direction = 'LONG'),
                   count(*) filter (where g.guessed_direction = 'SHORT'),
                   count(*) filter (where g.correct),
                   count(distinct g.user_id),
                   count(*) filter (where g.mode = 'PRACTICE'),
                   count(*) filter (where g.mode = 'DAILY'),
                   count(*) filter (where g.mode = 'ARCHIVE')
            from guess_results g
            where g.created_at >= :since and g.created_at < :until
            group by bucket
            order by bucket
            """, nativeQuery = true)
    List<Object[]> bucketed(@Param("unit") String unit,
                            @Param("since") Instant since,
                            @Param("until") Instant until);

    /**
     * How many distinct players touched each game mode in a window, as rows of [mode, players].
     *
     * Separate from {@link #bucketed} because it cannot be summed out of it: a player who
     * practised on Monday and again on Friday is one player over the window and two across
     * the buckets, and adding bucket figures up would report the second number under a label
     * promising the first.
     *
     * Modes nobody played are absent rather than zero, the way a {@code group by} leaves
     * them; the caller fills the gap, because "nobody played the daily this week" is a
     * figure the pane has to draw rather than a row it can wait for.
     */
    @Query(value = """
            select g.mode, count(distinct g.user_id)
            from guess_results g
            where g.created_at >= :since and g.created_at < :until
            group by g.mode
            """, nativeQuery = true)
    List<Object[]> modePlayersBetween(@Param("since") Instant since, @Param("until") Instant until);

    /** [guesses, correct] between two instants — the numerator and denominator of a delta. */
    @Query(value = "select count(g), coalesce(sum(case when g.correct then 1 else 0 end), 0)"
            + " from GuessResult g where g.createdAt >= :since and g.createdAt < :until")
    Object[] activityBetween(@Param("since") Instant since, @Param("until") Instant until);

    /** Distinct players who guessed between two instants. */
    @Query("select count(distinct g.user.id) from GuessResult g"
            + " where g.createdAt >= :since and g.createdAt < :until")
    long activePlayersBetween(@Param("since") Instant since, @Param("until") Instant until);

    /**
     * Every recorded result, grouped by player and in play order, as rows of
     * [userId, correct].
     *
     * The leaderboard ranks on score, and score depends on the order guesses were made — a
     * streak is worth more than the same guesses shuffled — so it cannot be reached with
     * SUM/COUNT. This is the one query that feeds the whole board: it walks
     * `idx_guess_results_user_time`, which is already ordered exactly this way, and the
     * folding happens once in Java via PlayerScore.
     */
    @Query("select g.user.id, g.correct from GuessResult g order by g.user.id, g.createdAt")
    List<Object[]> resultFlagsByUserInPlayOrder();
}

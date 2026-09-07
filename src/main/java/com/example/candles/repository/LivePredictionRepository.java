package com.example.candles.repository;

import com.example.candles.entity.Asset;
import com.example.candles.entity.LivePrediction;
import com.example.candles.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LivePredictionRepository extends JpaRepository<LivePrediction, Long> {

    Optional<LivePrediction> findByUserAndAssetAndTimeframeAndOpenTime(
            User user, Asset asset, String timeframe, Instant openTime);

    /**
     * Both sides of the crowd for one round, in one row rather than two queries — the split is
     * drawn as a pair of percentages, and two queries could disagree with each other while a
     * prediction landed between them.
     */
    @Query("""
            select coalesce(sum(case when p.direction = com.example.candles.entity.Direction.LONG then 1 else 0 end), 0),
                   coalesce(sum(case when p.direction = com.example.candles.entity.Direction.SHORT then 1 else 0 end), 0)
            from LivePrediction p
            where p.asset.id = :assetId and p.timeframe = :timeframe and p.openTime = :openTime
            """)
    List<Object[]> countSides(@Param("assetId") Long assetId,
                              @Param("timeframe") String timeframe,
                              @Param("openTime") Instant openTime);

    /**
     * The same split as {@link #countSides}, batched over every round in one page of history —
     * one query instead of one per row. Rounds nobody called are simply absent from the result;
     * the caller fills those in as [0, 0].
     */
    @Query("""
            select p.openTime,
                   coalesce(sum(case when p.direction = com.example.candles.entity.Direction.LONG then 1 else 0 end), 0),
                   coalesce(sum(case when p.direction = com.example.candles.entity.Direction.SHORT then 1 else 0 end), 0)
            from LivePrediction p
            where p.asset.id = :assetId and p.timeframe = :timeframe and p.openTime in :openTimes
            group by p.openTime
            """)
    List<Object[]> countSidesForRounds(@Param("assetId") Long assetId,
                                       @Param("timeframe") String timeframe,
                                       @Param("openTimes") List<Instant> openTimes);

    /**
     * Who called this round and which way, newest call first — the pool's own roster, the same
     * social-proof read a live crowd gives at a glance. {@code join fetch user} because every
     * row is about to read {@code getUser().getDisplayName()}; without it this would be N+1
     * queries for a list nobody would otherwise notice growing.
     */
    @Query("""
            select p from LivePrediction p join fetch p.user
            where p.asset.id = :assetId and p.timeframe = :timeframe and p.openTime = :openTime
            order by p.createdAt desc
            """)
    List<LivePrediction> findParticipants(@Param("assetId") Long assetId,
                                          @Param("timeframe") String timeframe,
                                          @Param("openTime") Instant openTime);

    /** A player's own calls, newest first, for scoring their live-round record. */
    List<LivePrediction> findByUserOrderByOpenTimeDesc(User user);

    /**
     * Fragment shared by both queries below: a settled live call, as [createdAt, correct],
     * with "settled" meaning exactly "a row exists in candles for this call's (asset,
     * timeframe, open_time)" — the same join {@code LiveRoundService.history} does. An open
     * round simply has no matching candle yet and drops out of the join, contributing nothing
     * until it closes; there is still no settlement job anywhere, this just reads the same
     * always-current comparison two other call sites already make.
     */
    String SETTLED_LIVE_FLAGS = """
            select p.user_id, p.created_at,
                   case when (p.direction = 'LONG' and c.close >= c.open)
                          or (p.direction = 'SHORT' and c.close < c.open)
                        then true else false end as correct
            from live_predictions p
            join candles c on c.asset_id = p.asset_id
                           and c.timeframe = p.timeframe
                           and c.open_time = p.open_time
            """;

    /**
     * One player's correct/incorrect flags, practice and live combined, in the order the calls
     * were actually made — a live call sorts by when it was placed, not when its candle later
     * closed, so a streak reads the same way the player experienced it.
     *
     * {@link com.example.candles.domain.PlayerScore} cannot be handed two separate streams and
     * reconciled after the fact (see its own docs on why score isn't a SUM), so the interleave
     * has to happen before the flags reach it — here, in one query, rather than a merge-sort in
     * Java over two already-sorted lists.
     */
    @Query(value = "select correct from ("
            + "select created_at, correct from guess_results where user_id = :userId "
            + "union all "
            + "select created_at, correct from (" + SETTLED_LIVE_FLAGS + ") live where user_id = :userId"
            + ") combined order by created_at", nativeQuery = true)
    List<Boolean> combinedResultFlagsInPlayOrder(@Param("userId") Long userId);

    /**
     * Every player's combined flags at once, grouped and ordered exactly like
     * {@link GuessResultRepository#resultFlagsByUserInPlayOrder()} — the leaderboard folds
     * these the same way, one player at a time, in one pass.
     */
    @Query(value = "select user_id, correct from ("
            + "select user_id, created_at, correct from guess_results "
            + "union all "
            + "select user_id, created_at, correct from (" + SETTLED_LIVE_FLAGS + ") live"
            + ") combined order by user_id, created_at", nativeQuery = true)
    List<Object[]> combinedResultFlagsByUserInPlayOrder();

    /**
     * The distinct UTC days this player called anything on, newest first — practice and live
     * together, which is what {@link com.example.candles.domain.PlayStreak} folds into a
     * day streak.
     *
     * A live call counts on the day it was placed whether or not its candle has closed since,
     * so this deliberately does not reuse {@link #SETTLED_LIVE_FLAGS}: turning up and calling a
     * round is the thing a day streak measures, and an open round is not a day the player
     * failed to show up for.
     *
     * The pattern quiz is unioned in for the same reason, and it is the only place it is: its
     * rows are invisible to score, the leaderboard, retention and badges on purpose (naming a
     * pattern and calling a direction are not the same currency), but answering it is still
     * turning up, which is all this streak claims to measure. Its day is already a date, so it
     * needs no cast.
     */
    @Query(value = """
            select distinct cast(d.created_at at time zone 'UTC' as date) as day
            from (
                select created_at from guess_results where user_id = :userId
                union all
                select created_at from live_predictions where user_id = :userId
            ) d
            union
            select day from pattern_quiz_results where user_id = :userId
            order by day desc
            """, nativeQuery = true)
    List<LocalDate> distinctPlayDaysDesc(@Param("userId") Long userId);

    /**
     * Every call anyone has made, practice and live in one stream, with no settlement join —
     * retention asks who turned up, not who was right.
     *
     * Admin accounts are dropped here rather than filtered afterwards, for the same reason
     * {@code LeaderboardService} drops them: the seeded admin plays far more than any real
     * player while the app is being tested, and a retention figure that is mostly one developer
     * coming back to their own test account measures nothing.
     */
    String ALL_PLAYS = """
            select p.user_id, p.created_at
            from (select user_id, created_at from guess_results
                  union all
                  select user_id, created_at from live_predictions) p
            join users u on u.id = p.user_id and u.role <> 'ADMIN'
            """;

    /**
     * Cohort retention, as rows of [cohortDay, newPlayers, returnedNextDay, returnedWithinWeek].
     *
     * A cohort is everyone whose *first* recorded call landed on that UTC day — first play, not
     * sign-up: an account created and never played has not been retained or lost, it has not
     * started. "Returned" is a different day with a call on it, so the cohort day itself never
     * counts as a return.
     *
     * The two return columns answer two different questions and the caller must not treat them
     * as one: {@code returnedNextDay} is the strict next day, {@code returnedWithinWeek} is any
     * of the seven days after. At this app's volume the strict figure is mostly noise, which is
     * why the looser one is here beside it rather than instead of it.
     */
    @Query(value = "with plays as ("
            + "  select user_id, cast(created_at at time zone 'UTC' as date) as day"
            + "  from (" + ALL_PLAYS + ") q group by 1, 2"
            + "), cohorts as ("
            + "  select user_id, min(day) as cohort_day from plays group by user_id"
            + ") "
            + "select c.cohort_day, count(distinct c.user_id),"
            + "       count(distinct c.user_id) filter (where p.day = c.cohort_day + 1),"
            + "       count(distinct c.user_id) filter (where p.day > c.cohort_day and p.day <= c.cohort_day + 7)"
            + " from cohorts c left join plays p on p.user_id = c.user_id"
            + " where c.cohort_day >= :since"
            + " group by c.cohort_day order by c.cohort_day", nativeQuery = true)
    List<Object[]> retentionCohorts(@Param("since") LocalDate since);

    /**
     * Rows of [day, plays, activePlayers] in UTC — the denominator and numerator of "calls per
     * active player per day", which is the other half of whether a change made people play more
     * or merely made more people show up once.
     */
    @Query(value = "select cast(p.created_at at time zone 'UTC' as date) as day,"
            + " count(*), count(distinct p.user_id)"
            + " from (" + ALL_PLAYS + ") p"
            + " where p.created_at >= :since"
            + " group by day order by day", nativeQuery = true)
    List<Object[]> dailyActivitySince(@Param("since") Instant since);

    /**
     * [calls, settled, correctSettled] since an instant — every live call is counted the moment
     * it is placed, so "calls" moves in real time even though "settled" and "correctSettled"
     * only move once a call's candle closes, through the same left join
     * {@link #combinedResultFlagsByUserInPlayOrder} makes an inner join of. An open round is
     * real activity with no verdict yet, not activity that has not happened — the ops panel
     * needs to be able to tell those apart, which a straight reuse of the settled-only fragment
     * above could not.
     */
    @Query(value = """
            select count(*),
                   count(c.open_time),
                   count(*) filter (
                       where c.open_time is not null
                         and ((p.direction = 'LONG' and c.close >= c.open)
                           or (p.direction = 'SHORT' and c.close < c.open))
                   )
            from live_predictions p
            left join candles c on c.asset_id = p.asset_id
                                and c.timeframe = p.timeframe
                                and c.open_time = p.open_time
            where p.created_at >= :since
            """, nativeQuery = true)
    Object[] liveActivitySince(@Param("since") Instant since);
}

package com.example.candles.repository;

import com.example.candles.entity.Asset;
import com.example.candles.entity.LivePrediction;
import com.example.candles.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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

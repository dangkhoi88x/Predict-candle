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
}

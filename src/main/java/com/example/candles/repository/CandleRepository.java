package com.example.candles.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.candles.entity.Asset;
import com.example.candles.entity.Candle;

public interface CandleRepository extends JpaRepository<Candle, Long> {

    Optional<Candle> findTopByAssetAndTimeframeOrderByOpenTimeDesc(Asset asset, String timeframe);

    /** The one settled candle for a given round, once it exists — the live game reads its outcome this way. */
    Optional<Candle> findByAssetAndTimeframeAndOpenTime(Asset asset, String timeframe, Instant openTime);

    /** Most recent settled candles, newest first — the live game's round history strip. */
    List<Candle> findByAssetAndTimeframeOrderByOpenTimeDesc(Asset asset, String timeframe, Pageable page);

    /** The neighbourhood around one round, oldest first — the round-detail popup's context chart. */
    List<Candle> findByAssetAndTimeframeAndOpenTimeBetweenOrderByOpenTimeAsc(
            Asset asset, String timeframe, Instant from, Instant to);

    long countByAssetAndTimeframe(Asset asset, String timeframe);

    @Query(value = """
            SELECT * FROM candles c
            WHERE c.asset_id = :assetId AND c.timeframe = :timeframe
            ORDER BY c.open_time ASC
            OFFSET :offset LIMIT :limit
            """, nativeQuery = true)
    List<Candle> findWindow(@Param("assetId") Long assetId,
                             @Param("timeframe") String timeframe,
                             @Param("offset") int offset,
                             @Param("limit") int limit);
}

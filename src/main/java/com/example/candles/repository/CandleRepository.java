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

    /**
     * The same count, but as of an instant — how much history existed before a given moment.
     *
     * A daily round draws its window out of this rather than out of the live count, which grows
     * every time the hourly sync lands. Seeding the draw is not enough on its own: the same seed
     * against a range that got one wider picks a different number, so a round chosen at 10:00
     * and the "same" round chosen at 11:00 would be different charts. Counting only what closed
     * before the day began holds the range still for the whole day, and grows it by exactly a
     * day's candles at midnight.
     *
     * This works because {@link #findWindow} offsets from the oldest candle: freshly synced
     * candles land at the end and shift nothing, so an index means the same chart tomorrow as
     * it does today.
     */
    long countByAssetAndTimeframeAndOpenTimeLessThan(Asset asset, String timeframe, Instant openTime);

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

package com.example.candles.repository;

import com.example.candles.domain.GuessResult;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface GuessResultRepository extends JpaRepository<GuessResult, Long> {

    /**
     * Written out rather than derived from the method name, which would run to well over a
     * hundred characters for these five columns.
     */
    @Query("""
            select count(g) > 0 from GuessResult g
            where g.user.id = :userId
              and g.asset.id = :assetId
              and g.timeframe = :timeframe
              and g.startIndex = :startIndex
              and g.guessNumber = :guessNumber
            """)
    boolean alreadyRecorded(@Param("userId") Long userId,
                            @Param("assetId") Long assetId,
                            @Param("timeframe") String timeframe,
                            @Param("startIndex") int startIndex,
                            @Param("guessNumber") int guessNumber);

    /**
     * Just the flags, in play order. Streak length cannot be reached with count/sum, and
     * selecting the column rather than the entity keeps this off the hydration path.
     */
    @Query("select g.correct from GuessResult g where g.user.id = :userId order by g.createdAt")
    List<Boolean> resultFlagsInPlayOrder(@Param("userId") Long userId);

    /** Newest first. Pageable rather than a limit clause, which JPQL has no portable form of. */
    @Query("select g from GuessResult g join fetch g.asset where g.user.id = :userId order by g.createdAt desc")
    List<GuessResult> findRecent(@Param("userId") Long userId, Pageable pageable);

    /** Rows of [symbol, total, correct]. */
    @Query("""
            select a.symbol, count(g), sum(case when g.correct then 1 else 0 end)
            from GuessResult g join g.asset a
            where g.user.id = :userId
            group by a.symbol
            order by count(g) desc
            """)
    List<Object[]> tallyByAsset(@Param("userId") Long userId);
}

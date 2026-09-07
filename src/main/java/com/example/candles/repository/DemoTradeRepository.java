package com.example.candles.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

import com.example.candles.entity.DemoTrade;

public interface DemoTradeRepository extends JpaRepository<DemoTrade, Long> {

    /**
     * One account's trades since it was last opened, oldest first — the order the fold needs.
     *
     * {@code since} is what makes a reset work without deleting anything: the rows before the
     * mark stay on disk and simply stop being read.
     */
    @Query("""
            select t from DemoTrade t join fetch t.asset
            where t.userId = :userId and t.createdAt >= :since
            order by t.createdAt, t.id
            """)
    List<DemoTrade> findSince(@Param("userId") Long userId, @Param("since") Instant since);
}

package com.example.candles.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.candles.entity.AppError;

public interface AppErrorRepository extends JpaRepository<AppError, Long> {

    /** Newest first — what the ops pane lists, and the row a repeat folds into. */
    List<AppError> findByOrderByLastAtDescIdDesc(Pageable page);

    Optional<AppError> findFirstByOrderByLastAtDescIdDesc();

    int deleteByLastAtBefore(Instant cutoff);

    /**
     * Drops everything older than the newest {@code keep} rows — the other half of retention. Age
     * alone cannot bound the table: a bad night can write thousands of rows inside the window.
     */
    @Modifying
    @Query(value = """
            delete from app_errors
            where id not in (select id from app_errors order by last_at desc, id desc limit :keep)
            """, nativeQuery = true)
    int trimTo(@Param("keep") int keep);
}

package com.example.candles.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.example.candles.entity.PatternQuizResult;

public interface PatternQuizResultRepository extends JpaRepository<PatternQuizResult, Long> {

    Optional<PatternQuizResult> findByUserIdAndDay(Long userId, LocalDate day);

    /** [answers, correct] for one day — how the quiz went, for the admin's day-by-day list. */
    @Query("""
            select count(r), coalesce(sum(case when r.correct then 1 else 0 end), 0)
            from PatternQuizResult r where r.day = :day
            """)
    Object[] tallyForDay(@Param("day") LocalDate day);

    /**
     * Which pattern a past day asked about, read off an answer somebody gave.
     *
     * Every row for a day carries the same {@code patternId} — it is the question, not the
     * answer — so any one of them says what was asked. Reading it back is far cheaper than
     * rebuilding the question, which means scanning every asset's whole history against every
     * pattern in the library for a day already over.
     */
    @Query("""
            select r.patternId from PatternQuizResult r where r.day = :day order by r.id
            """)
    List<String> askedPatternIds(@Param("day") LocalDate day, Pageable pageable);
}

package com.example.candles.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

import com.example.candles.entity.PatternQuizResult;

public interface PatternQuizResultRepository extends JpaRepository<PatternQuizResult, Long> {

    Optional<PatternQuizResult> findByUserIdAndDay(Long userId, LocalDate day);
}

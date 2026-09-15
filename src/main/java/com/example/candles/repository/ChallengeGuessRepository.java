package com.example.candles.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

import com.example.candles.entity.ChallengeGuess;

public interface ChallengeGuessRepository extends JpaRepository<ChallengeGuess, Long> {

    List<ChallengeGuess> findByChallengeIdAndUserIdOrderByGuessNumber(String challengeId, Long userId);

    boolean existsByChallengeIdAndUserIdAndGuessNumber(String challengeId, Long userId, int guessNumber);

    /**
     * Everybody who has finished the challenge — {@code total} guesses in — as
     * {@code [User, correct, finishedAt]}, best first and, between equals, whoever finished first.
     */
    @Query("""
            select g.user, sum(case when g.correct = true then 1 else 0 end), max(g.createdAt)
            from ChallengeGuess g
            where g.challenge.id = :challengeId
            group by g.user
            having count(g) >= :total
            order by sum(case when g.correct = true then 1 else 0 end) desc, max(g.createdAt) asc
            """)
    List<Object[]> finishers(@Param("challengeId") String challengeId, @Param("total") long total, Pageable page);
}

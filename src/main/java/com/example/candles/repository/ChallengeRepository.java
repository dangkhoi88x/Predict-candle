package com.example.candles.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

import com.example.candles.entity.Challenge;

public interface ChallengeRepository extends JpaRepository<Challenge, String> {

    Optional<Challenge> findFirstByCreatorIdAndAssetIdAndTimeframeAndStartIndex(
            Long creatorId, Long assetId, String timeframe, int startIndex);
}

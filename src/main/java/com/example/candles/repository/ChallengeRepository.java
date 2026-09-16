package com.example.candles.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

import com.example.candles.entity.Challenge;

public interface ChallengeRepository extends JpaRepository<Challenge, String> {

    Optional<Challenge> findFirstByCreatorIdAndAssetIdAndTimeframeAndStartIndex(
            Long creatorId, Long assetId, String timeframe, int startIndex);

    /**
     * Detaches a deleted account from the links it made. The links keep working for the people
     * already playing them — that is why the foreign key is SET NULL rather than CASCADE — but the
     * name stored beside it is overwritten here, because SET NULL alone would leave the deleted
     * player's name on every link they ever sent.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Challenge c set c.creator = null, c.creatorName = :name where c.creator.id = :userId")
    int forgetCreator(@Param("userId") Long userId, @Param("name") String name);
}

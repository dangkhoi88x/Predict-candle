package com.example.candles.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import com.example.candles.dto.response.DailyRoundResponse;
import com.example.candles.entity.Asset;
import com.example.candles.entity.AssetType;
import com.example.candles.entity.Direction;
import com.example.candles.entity.GuessMode;
import com.example.candles.entity.GuessResult;
import com.example.candles.entity.Role;
import com.example.candles.entity.User;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.GuessResultRepository;
import com.example.candles.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How hard a chart turned out to be, folded out of the calls already recorded on it.
 *
 * The two things worth pinning are both about saying less than the rows support: a candle only a
 * handful of people answered is left out, and a chart where none of them clears the floor sends
 * nothing at all rather than a block that reads as a crowd getting everything wrong.
 */
@SpringBootTest
@Transactional
class CommunityDifficultyTest {

    @Autowired private CommunityDifficultyService difficulty;
    @Autowired private GuessResultRepository guessResults;
    @Autowired private AssetRepository assets;
    @Autowired private UserRepository users;

    private static final String TIMEFRAME = "1h";
    private static final int START_INDEX = 4242;

    private Asset pair() {
        return assets.saveAndFlush(new Asset(
                "CD" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(), "Community pair", AssetType.CRYPTO));
    }

    /** {@code players} answer guess {@code guessNumber} of one chart, {@code correct} of them right. */
    private void crowd(Asset asset, int guessNumber, int players, int correct) {
        for (int i = 0; i < players; i++) {
            User user = new User("0x" + UUID.randomUUID().toString().replace("-", ""), "P" + i);
            user.assignRole(Role.USER);
            user = users.saveAndFlush(user);
            Direction guessed = i < correct ? Direction.LONG : Direction.SHORT;
            guessResults.save(new GuessResult(user, asset, TIMEFRAME, START_INDEX, guessNumber,
                    guessed, Direction.LONG, GuessMode.DAILY));
        }
        guessResults.flush();
    }

    @Test
    void aCandleEnoughPeopleAnsweredCarriesItsRate() {
        Asset asset = pair();
        crowd(asset, 1, CommunityDifficultyService.MIN_PLAYERS, 7);
        crowd(asset, 2, CommunityDifficultyService.MIN_PLAYERS + 2, 3);

        DailyRoundResponse.Community community =
                difficulty.forChart(GuessMode.DAILY, asset.getId(), TIMEFRAME, START_INDEX);

        assertThat(community).isNotNull();
        assertThat(community.minPlayers()).isEqualTo(CommunityDifficultyService.MIN_PLAYERS);
        assertThat(community.guesses()).extracting(DailyRoundResponse.GuessRate::guessNumber)
                .containsExactly(1, 2);
        assertThat(community.guesses().getFirst().correct()).isEqualTo(7);
        assertThat(community.guesses().get(1).players())
                .isEqualTo(CommunityDifficultyService.MIN_PLAYERS + 2L);
    }

    @Test
    void acandleTooFewHaveAnsweredIsLeftOutAndAQuietChartSendsNothing() {
        Asset asset = pair();
        crowd(asset, 1, CommunityDifficultyService.MIN_PLAYERS, 5);
        crowd(asset, 2, CommunityDifficultyService.MIN_PLAYERS - 1, 1);

        DailyRoundResponse.Community community =
                difficulty.forChart(GuessMode.DAILY, asset.getId(), TIMEFRAME, START_INDEX);
        assertThat(community.guesses()).extracting(DailyRoundResponse.GuessRate::guessNumber)
                .as("a rate over nine people is nine people")
                .containsExactly(1);

        assertThat(difficulty.forChart(GuessMode.DAILY, pair().getId(), TIMEFRAME, START_INDEX))
                .as("nothing at all beats an empty block that reads as everyone being wrong")
                .isNull();
    }

    /** The daily and its archive replay are different crowds on the same coordinates. */
    @Test
    void eachModeHasItsOwnCrowd() {
        Asset asset = pair();
        crowd(asset, 1, CommunityDifficultyService.MIN_PLAYERS, 6);

        assertThat(difficulty.forChart(GuessMode.DAILY, asset.getId(), TIMEFRAME, START_INDEX)).isNotNull();
        assertThat(difficulty.forChart(GuessMode.ARCHIVE, asset.getId(), TIMEFRAME, START_INDEX)).isNull();
        assertThat(difficulty.forChart(GuessMode.PRACTICE, asset.getId(), TIMEFRAME, START_INDEX)).isNull();
    }
}

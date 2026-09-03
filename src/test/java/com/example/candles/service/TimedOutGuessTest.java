package com.example.candles.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

import com.example.candles.dto.response.StatsResponse;
import com.example.candles.entity.Asset;
import com.example.candles.entity.Direction;
import com.example.candles.entity.GuessResult;
import com.example.candles.entity.Role;
import com.example.candles.entity.User;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.GuessResultRepository;
import com.example.candles.repository.UserRepository;
import com.example.candles.security.JwtService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A guess the countdown ate has no direction — V3 made the column nullable so that "no answer"
 * and "wrong answer" stay different rows.
 *
 * The profile read did not honour that: it called name() on the direction, so a single expired
 * clock turned GET /api/stats/me into a 500 and blanked the player's whole profile from then
 * on. Nothing failed at write time and nothing failed on a fresh account, which is why it sat
 * unnoticed — the account has to time out once before it breaks, and then it never recovers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TimedOutGuessTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private StatsService statsService;
    @Autowired private UserRepository users;
    @Autowired private AssetRepository assets;
    @Autowired private GuessResultRepository guessResults;
    @Autowired private JwtService jwt;

    private User playerWhoRanOutOfTime() {
        User user = users.saveAndFlush(
                new User("0x" + UUID.randomUUID().toString().replace("-", ""), "T"));
        Asset asset = assets.findAll().getFirst();
        guessResults.saveAll(java.util.List.of(
                new GuessResult(user, asset, "1h", 1, 1, Direction.LONG, Direction.LONG),
                // No direction: the clock ran out before an answer.
                new GuessResult(user, asset, "1h", 2, 1, null, Direction.SHORT)));
        guessResults.flush();
        return user;
    }

    @Test
    void aTimedOutGuessDoesNotBlowUpTheProfile() {
        User user = playerWhoRanOutOfTime();

        StatsResponse stats = statsService.forUser(user.getId());

        assertThat(stats.recent()).hasSize(2);
        assertThat(stats.recent()).anySatisfy(g -> assertThat(g.guessed()).isNull());
        // The unanswered one still counts as a guess and still is not correct — the totals
        // must not quietly drop it just because it has no direction.
        assertThat(stats.recorded().total()).isEqualTo(2);
        assertThat(stats.recorded().correct()).isEqualTo(1);
        assertThat(stats.recent()).allSatisfy(g -> assertThat(g.actual()).isNotBlank());
    }

    @Test
    void theEndpointAnswers200RatherThan500() throws Exception {
        User user = playerWhoRanOutOfTime();
        user.assignRole(Role.USER);

        mockMvc.perform(get("/api/stats/me")
                        .header("Authorization", "Bearer " + jwt.createAccessToken(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recorded.total").value(2))
                // Serialised as an explicit null, so the client can tell "no answer" from a
                // direction rather than printing "đoán null".
                .andExpect(jsonPath("$.recent[?(@.guessed == null)]").exists());
    }
}

package com.example.candles.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.LongStream;

import com.example.candles.CandleFixture;
import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.PlayerInsights;
import com.example.candles.entity.Asset;
import com.example.candles.entity.Candle;
import com.example.candles.entity.Direction;
import com.example.candles.entity.GuessMode;
import com.example.candles.entity.GuessResult;
import com.example.candles.entity.Role;
import com.example.candles.entity.User;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.CandleRepository;
import com.example.candles.repository.GuessResultRepository;
import com.example.candles.repository.UserRepository;
import com.example.candles.security.JwtService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The habits section against the real database — the two things a pure {@link PlayerInsights}
 * test cannot reach: that a guess row is joined to the candles it was actually made on, and the
 * JSON names profile.js reads.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InsightsFlowTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private AssetRepository assets;
    @Autowired private CandleRepository candles;
    @Autowired private GuessResultRepository guessResults;
    @Autowired private CandlesProperties properties;
    @Autowired private JwtService jwt;

    private Asset asset;

    @BeforeEach
    void seed() {
        asset = assets.findAllByOrderByPositionAscSymbolAsc().getFirst();
        CandleFixture.seedIfEmpty(candles, asset, "1h");
    }

    private User player() {
        User user = new User("0x" + UUID.randomUUID().toString().replace("-", ""), "I");
        user.assignRole(Role.USER);
        return users.saveAndFlush(user);
    }

    /**
     * The index a guess row stores means the same candle to {@code candlesAtIndexes} as it does to
     * {@code findWindow}, which is what dealt the chart. If the two numbered candles differently,
     * every trend on the profile would be read off the wrong part of the chart and nothing would
     * look broken.
     */
    @Test
    void candlesAreAddressedByTheSameIndexThatDealtTheChart() {
        List<Long> positions = List.of(0L, 1L, 57L, 500L, 999L);
        List<Object[]> rows = candles.candlesAtIndexes(asset.getId(), "1h", positions);

        assertThat(rows).hasSize(positions.size());
        for (Object[] row : rows) {
            int index = ((Number) row[0]).intValue();
            Candle dealt = candles.findWindow(asset.getId(), "1h", index, 1).getFirst();
            assertThat(((Number) row[1]).longValue()).isEqualTo(dealt.getOpenTime().toEpochMilli());
            assertThat(row[2]).isEqualTo(dealt.getOpen());
            assertThat(row[5]).isEqualTo(dealt.getClose());
        }
    }

    @Test
    void recentCallsComeBackAsHabitsJudgedOnTheCandlesTheyWereMadeOn() throws Exception {
        User player = player();
        int visible = properties.round().visibleCandles();

        // 36 calls, every one LONG, on 36 charts spread through the history, each the third guess
        // of its chart. What each chart had just done is worked out independently from findWindow.
        Map<PlayerInsights.Trend, Integer> expected = new EnumMap<>(PlayerInsights.Trend.class);
        int ups = 0;
        for (int i = 0; i < 36; i++) {
            int start = 10 + i * 25;
            int guessNumber = 3;
            int lastVisible = start + visible + guessNumber - 2;
            List<Candle> seen = candles.findWindow(asset.getId(), "1h", lastVisible - 4, 5);
            expected.merge(PlayerInsights.trendOf(seen), 1, Integer::sum);

            Candle answer = candles.findWindow(asset.getId(), "1h", lastVisible + 1, 1).getFirst();
            Direction actual = answer.getClose().compareTo(answer.getOpen()) >= 0 ? Direction.LONG : Direction.SHORT;
            if (actual == Direction.LONG) ups++;
            guessResults.save(new GuessResult(player, asset, "1h", start, guessNumber, Direction.LONG, actual, GuessMode.PRACTICE));
        }
        guessResults.flush();

        var result = mockMvc.perform(get("/api/stats/me/insights")
                        .header("Authorization", "Bearer " + jwt.createAccessToken(player)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysed").value(36))
                .andExpect(jsonPath("$.window").value(500))
                .andExpect(jsonPath("$.minSample").value(PlayerInsights.MIN_SAMPLE))
                .andExpect(jsonPath("$.enough").value(true))
                .andExpect(jsonPath("$.timedOut").value(0))
                .andExpect(jsonPath("$.calls.longCalls").value(36))
                .andExpect(jsonPath("$.calls.shortCalls").value(0))
                .andExpect(jsonPath("$.calls.marketUp").value(ups))
                .andExpect(jsonPath("$.calls.correctLong").value(ups))
                .andExpect(jsonPath("$.sessions.length()").value(4))
                .andExpect(jsonPath("$.findings[0].kind").exists())
                .andExpect(jsonPath("$.findings[0].gapPoints").exists());

        for (PlayerInsights.Trend trend : PlayerInsights.Trend.values()) {
            result.andExpect(jsonPath("$.trends[?(@.trend == '" + trend.name() + "')].total")
                    .value(expected.getOrDefault(trend, 0)));
        }
        // Calling LONG every time is a LONG bias unless the market happened to rise nine times in ten.
        if (ups * 100 / 36 <= 90 - PlayerInsights.GAP_POINTS) {
            result.andExpect(jsonPath("$.findings[?(@.kind == 'LONG_BIAS')]").exists());
        }
    }

    @Test
    void anAccountWithNoCallsReadsEmptyRatherThanAnError() throws Exception {
        User player = player();
        mockMvc.perform(get("/api/stats/me/insights")
                        .header("Authorization", "Bearer " + jwt.createAccessToken(player)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysed").value(0))
                .andExpect(jsonPath("$.enough").value(false))
                .andExpect(jsonPath("$.findings").isEmpty());
    }

    @Test
    void itIsPersonalSoSignedOutIsRefused() throws Exception {
        mockMvc.perform(get("/api/stats/me/insights")).andExpect(status().isUnauthorized());
    }
}

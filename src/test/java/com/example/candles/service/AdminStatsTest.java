package com.example.candles.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

import com.example.candles.dto.AdminStats;
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
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminStatsTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AdminStatsService statsService;
    @Autowired private UserRepository userRepository;
    @Autowired private AssetRepository assetRepository;
    @Autowired private GuessResultRepository guessResults;
    @Autowired private JwtService jwtService;

    /* The service caches for a minute, which is right in production and wrong across tests
       sharing one JVM: the second test would read the first one's answer. */
    @BeforeEach
    void freshCache() {
        statsService.evict();
    }

    private User save(Role role) {
        User user = new User("0x" + UUID.randomUUID().toString().replace("-", ""), "T");
        user.assignRole(role);
        return userRepository.saveAndFlush(user);
    }

    @Test
    void everyRangeReturnsAFullRunOfBucketsEndingNow() {
        assertThat(statsService.stats("week").buckets()).hasSize(7);
        assertThat(statsService.stats("month").buckets()).hasSize(12);
        assertThat(statsService.stats("year").buckets()).hasSize(5);

        AdminStats stats = statsService.stats("month");
        // Empty periods are present and zeroed. A chart that dropped them would draw a gap
        // as though the days either side were adjacent.
        assertThat(stats.buckets()).allSatisfy(b -> assertThat(b.start()).isNotNull());
        assertThat(stats.buckets()).isSortedAccordingTo(
                (a, b) -> a.start().compareTo(b.start()));
        // These windows are fixed regardless of range: the panels that read them say so.
        assertThat(stats.daily()).hasSize(14);
        assertThat(stats.weekly()).hasSize(12);
        assertThat(stats.accounts()).hasSize(14);
    }

    @Test
    void accuracyHeadlineAndLineShareOneDenominator() {
        User player = save(Role.USER);
        Asset asset = assetRepository.findAll().getFirst();
        guessResults.saveAll(List.of(
                new GuessResult(player, asset, "1h", 11, 1, Direction.LONG, Direction.LONG),
                // Timed out: no direction. It still counts against accuracy, the way
                // PlayerScore counts it against the player.
                new GuessResult(player, asset, "1h", 12, 1, null, Direction.LONG)));
        guessResults.flush();

        AdminStats stats = statsService.stats("month");
        long guesses = stats.weekly().stream().mapToLong(AdminStats.Bucket::guesses).sum();
        long correct = stats.weekly().stream().mapToLong(AdminStats.Bucket::correct).sum();

        // The headline is the same measurement over the same window as the plotted line, and
        // both count unanswered guesses. Dividing by `answered` instead reads several points
        // high, which is what made the number and the line under it disagree.
        assertThat(stats.totals().guesses()).isEqualTo(guesses);
        assertThat(stats.totals().accuracy()).isEqualTo((double) correct / guesses);

        long answered = stats.weekly().stream().mapToLong(AdminStats.Bucket::answered).sum();
        assertThat(answered).isLessThan(guesses);
    }

    @Test
    void theAccountDeltaMeasuresTheAccountTotalItSitsUnder() {
        long before = userRepository.count();
        save(Role.USER);
        statsService.evict();

        AdminStats stats = statsService.stats("month");

        // Value, sparkline and delta are one quantity: accounts in existence.
        assertThat(stats.totals().accounts()).isEqualTo(before + 1);
        assertThat(stats.accounts().getLast().total()).isEqualTo(before + 1);
        assertThat(stats.accounts().getLast().added()).isPositive();
        assertThat(stats.accounts()).isSortedAccordingTo(
                (a, b) -> a.start().compareTo(b.start()));
    }

    @Test
    void freshSkipsTheCacheAndPlainReadsUseIt() {
        long before = statsService.stats("month").totals().accounts();
        save(Role.USER);

        assertThat(statsService.stats("month").totals().accounts()).isEqualTo(before);
        assertThat(statsService.stats("month", true).totals().accounts()).isEqualTo(before + 1);
    }

    @Test
    void anUnknownRangeFallsBackToTheMonthView() {
        assertThat(statsService.stats("fortnight").range()).isEqualTo("month");
        assertThat(statsService.stats(null).range()).isEqualTo("month");
    }

    @Test
    void guessesLandInTodayBucketSplitByDirection() {
        User player = save(Role.USER);
        Asset asset = assetRepository.findAll().getFirst();
        guessResults.saveAll(List.of(
                new GuessResult(player, asset, "1h", 1, 1, Direction.LONG, Direction.LONG),
                new GuessResult(player, asset, "1h", 2, 1, Direction.LONG, Direction.SHORT),
                new GuessResult(player, asset, "1h", 3, 1, Direction.SHORT, Direction.SHORT),
                // Ran out of time: no direction was given, so it counts as neither side.
                new GuessResult(player, asset, "1h", 4, 1, null, Direction.LONG)));
        guessResults.flush();

        AdminStats.Bucket today = statsService.stats("week").buckets().getLast();

        assertThat(today.longCount()).isGreaterThanOrEqualTo(2);
        assertThat(today.shortCount()).isGreaterThanOrEqualTo(1);
        assertThat(today.answered()).isEqualTo(today.longCount() + today.shortCount());
        // The unanswered one is in `guesses` and in neither direction, so the chart's column
        // height and the accuracy denominator come apart here by design.
        assertThat(today.guesses()).isGreaterThan(today.answered());
        assertThat(today.correct()).isGreaterThanOrEqualTo(2);
        assertThat(today.activePlayers()).isPositive();
    }

    /**
     * The overview pane reads these paths by name out of the JSON and there is no shared
     * schema between the two, so renaming a record component is a silent break: the chart
     * just draws zeroes. This is the contract.
     */
    @Test
    void theJsonCarriesEveryFieldTheOverviewPaneReads() throws Exception {
        mockMvc.perform(get("/api/admin/stats?range=month")
                        .header("Authorization", "Bearer " + jwtService.createAccessToken(save(Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").value("month"))
                .andExpect(jsonPath("$.generatedAt").exists())
                .andExpect(jsonPath("$.buckets[0].start").exists())
                .andExpect(jsonPath("$.buckets[0].guesses").exists())
                .andExpect(jsonPath("$.buckets[0].longCount").exists())
                .andExpect(jsonPath("$.buckets[0].shortCount").exists())
                .andExpect(jsonPath("$.buckets[0].answered").exists())
                .andExpect(jsonPath("$.buckets[0].correct").exists())
                .andExpect(jsonPath("$.daily[0].guesses").exists())
                .andExpect(jsonPath("$.daily[0].activePlayers").exists())
                .andExpect(jsonPath("$.weekly[0].guesses").exists())
                .andExpect(jsonPath("$.weekly[0].correct").exists())
                .andExpect(jsonPath("$.accounts[0].total").exists())
                .andExpect(jsonPath("$.accounts[0].added").exists())
                .andExpect(jsonPath("$.totals.activePlayersToday").exists())
                .andExpect(jsonPath("$.totals.accounts").exists())
                // The four delta keys are nullable, so assert the object carries them rather
                // than that they hold a value.
                .andExpect(jsonPath("$.deltas").exists())
                .andExpect(jsonPath("$.deltas.*", hasSize(4)));
    }

    @Test
    void theEndpointIsClosedToEveryoneButAdmins() throws Exception {
        mockMvc.perform(get("/api/admin/stats?range=week"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/admin/stats?range=week")
                        .header("Authorization", "Bearer " + jwtService.createAccessToken(save(Role.USER))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/stats?range=week")
                        .header("Authorization", "Bearer " + jwtService.createAccessToken(save(Role.ADMIN))))
                .andExpect(status().isOk());
    }
}

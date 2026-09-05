package com.example.candles.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.example.candles.dto.response.OpsSnapshot;
import com.example.candles.entity.Asset;
import com.example.candles.entity.AssetType;
import com.example.candles.entity.Candle;
import com.example.candles.entity.Direction;
import com.example.candles.entity.LivePrediction;
import com.example.candles.entity.Role;
import com.example.candles.entity.User;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.CandleRepository;
import com.example.candles.repository.LivePredictionRepository;
import com.example.candles.repository.UserRepository;
import com.example.candles.security.JwtService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OpsSnapshotTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OpsService opsService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AssetRepository assetRepository;

    @Autowired
    private CandleRepository candleRepository;

    @Autowired
    private LivePredictionRepository livePredictionRepository;

    @Autowired
    private JwtService jwtService;

    private String tokenFor(Role role) {
        User user = new User("0x" + UUID.randomUUID().toString().replace("-", ""), "T");
        user.assignRole(role);
        userRepository.saveAndFlush(user);
        return "Bearer " + jwtService.createAccessToken(user);
    }

    @Test
    void snapshotReportsSchemaAssetsAndSettings() {
        OpsSnapshot snapshot = opsService.snapshot();

        /* Deliberately not asserting that every asset holds candles. That is only true after a
           backfill has reached Binance, so it made this test a check on the network: it passed
           on a developer machine with 40k candles per pair and failed in CI against an empty
           database, where the pairs exist and the fetch never happened. What the snapshot owes
           the operations panel is a row per configured pair, correctly described — a feed with
           no candles is a state it must report, not one it may fail on. */
        assertThat(snapshot.assets()).isNotEmpty()
                .allSatisfy(asset -> {
                    assertThat(asset.symbol()).isNotBlank();
                    assertThat(asset.timeframe()).isEqualTo("1h");
                    assertThat(asset.candles()).isNotNegative();
                    // An empty feed has no newest candle and no lag to measure, and the panel
                    // has to show it as behind rather than as healthy.
                    if (asset.candles() == 0) {
                        assertThat(asset.latestCandle()).isNull();
                        assertThat(asset.lagMinutes()).isNull();
                        assertThat(asset.stale()).isTrue();
                    } else {
                        assertThat(asset.latestCandle()).isNotNull();
                        assertThat(asset.lagMinutes()).isNotNull();
                    }
                });
        // Nothing may be waiting to run: the app validates its entities against the schema at
        // startup, so a pending migration means it is live on a schema it was not built for.
        assertThat(snapshot.schema().pendingMigrations()).isZero();
        assertThat(snapshot.schema().appliedMigrations()).isPositive();
        assertThat(snapshot.settings().visibleCandles()).isEqualTo(20);
        assertThat(snapshot.settings().guessSeconds()).isEqualTo(20);
        assertThat(snapshot.activity().contentItems()).isEqualTo(41);
    }

    @Test
    void staleIsJudgedAgainstTheTimeframeNotTheCount() {
        // A backfilled feed holds tens of thousands of candles whether or not it is still
        // being updated, so the flag has to come from the newest candle's age.
        assertThat(opsService.snapshot().assets())
                .allSatisfy(asset -> assertThat(asset.stale())
                        .isEqualTo(asset.lagMinutes() == null || asset.lagMinutes() > 130));
    }

    @Test
    void theWholePanelIsAdminOnly() throws Exception {
        mockMvc.perform(get("/api/admin/ops")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/ops").header("Authorization", tokenFor(Role.USER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/ops/sync/BTCUSDT").header("Authorization", tokenFor(Role.USER)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/ops").header("Authorization", tokenFor(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assets").isArray())
                .andExpect(jsonPath("$.schema.currentVersion").exists());
    }

    @Test
    void syncingAnAssetThatDoesNotExistIsRejected() throws Exception {
        mockMvc.perform(post("/api/admin/ops/sync/NOPEUSDT").header("Authorization", tokenFor(Role.ADMIN)))
                .andExpect(status().isBadRequest());
    }

    /**
     * Pins the JSON field names {@code admin-ops.js} reads off {@code activity} — a renamed
     * record component here would compile fine and just silently draw zeroes on the panel,
     * exactly the failure mode {@code AdminStatsTest} already guards against for its own KPIs.
     *
     * Asserts deltas rather than absolute counts: this test runs against whatever the
     * developer database already holds, and a call still in flight (no matching candle) must
     * move {@code liveCallsToday} without moving {@code liveSettledToday} or
     * {@code liveCorrectToday} at all.
     */
    @Test
    void liveRoundActivityCountsCallsSettledAndCorrectSeparately() throws Exception {
        OpsSnapshot.Activity before = opsService.snapshot().activity();

        Asset asset = assetRepository.saveAndFlush(
                new Asset("TEST" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(), "Test pair", AssetType.CRYPTO));
        User user = userRepository.saveAndFlush(new User("0x" + UUID.randomUUID().toString().replace("-", ""), "P"));
        Instant t1 = Instant.now().minusSeconds(60);
        Instant t2 = Instant.now().minusSeconds(120);
        Instant t3 = Instant.now().minusSeconds(180); // left open — no matching candle

        candleRepository.saveAndFlush(new Candle(asset, "1h", t1,
                new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("90"), new BigDecimal("105"), BigDecimal.TEN));
        livePredictionRepository.saveAndFlush(new LivePrediction(user, asset, "1h", t1, Direction.LONG)); // settled, correct

        candleRepository.saveAndFlush(new Candle(asset, "1h", t2,
                new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("90"), new BigDecimal("95"), BigDecimal.TEN));
        livePredictionRepository.saveAndFlush(new LivePrediction(user, asset, "1h", t2, Direction.LONG)); // settled, wrong

        livePredictionRepository.saveAndFlush(new LivePrediction(user, asset, "1h", t3, Direction.LONG)); // open

        OpsSnapshot.Activity after = opsService.snapshot().activity();

        assertThat(after.liveCallsToday() - before.liveCallsToday()).isEqualTo(3);
        assertThat(after.liveSettledToday() - before.liveSettledToday()).isEqualTo(2);
        assertThat(after.liveCorrectToday() - before.liveCorrectToday()).isEqualTo(1);

        mockMvc.perform(get("/api/admin/ops").header("Authorization", tokenFor(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activity.liveCallsToday").exists())
                .andExpect(jsonPath("$.activity.liveSettledToday").exists())
                .andExpect(jsonPath("$.activity.liveCorrectToday").exists())
                .andExpect(jsonPath("$.activity.liveCallsWeek").exists());
    }
}

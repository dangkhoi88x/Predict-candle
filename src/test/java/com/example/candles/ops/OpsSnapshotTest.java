package com.example.candles.ops;

import com.example.candles.auth.JwtService;
import com.example.candles.domain.Role;
import com.example.candles.domain.User;
import com.example.candles.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

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

        assertThat(snapshot.assets()).isNotEmpty()
                .allSatisfy(asset -> {
                    assertThat(asset.symbol()).isNotBlank();
                    assertThat(asset.candles()).isPositive();
                    assertThat(asset.latestCandle()).isNotNull();
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
}

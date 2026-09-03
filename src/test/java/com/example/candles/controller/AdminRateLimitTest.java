package com.example.candles.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

import com.example.candles.entity.Role;
import com.example.candles.entity.User;
import com.example.candles.repository.UserRepository;
import com.example.candles.security.JwtService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The admin surface has a ceiling.
 *
 * These are counted per signed-in user, so each test signs in as its own fresh admin and cannot
 * spend another test's quota — without that, running the class twice in one JVM would fail the
 * second time on counters left over from the first.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminRateLimitTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private JwtService jwt;

    private String freshAdmin() {
        User user = new User("0x" + UUID.randomUUID().toString().replace("-", ""), "T");
        user.assignRole(Role.ADMIN);
        return "Bearer " + jwt.createAccessToken(users.saveAndFlush(user));
    }

    /** Sends the same request until it is refused, and reports how many got through. */
    private int acceptedBefore429(String token, int attempts, java.util.function.Function<String, org.springframework.test.web.servlet.RequestBuilder> build) throws Exception {
        int ok = 0;
        for (int i = 0; i < attempts; i++) {
            int status = mockMvc.perform(build.apply(token)).andReturn().getResponse().getStatus();
            if (status == 429) return ok;
            ok++;
        }
        return ok;
    }

    @Test
    void theCacheSkippingStatsReadIsCappedWellBeforeAPlainOneIs() throws Exception {
        String token = freshAdmin();

        int fresh = acceptedBefore429(token, 40,
                t -> get("/api/admin/stats?range=week&fresh=true").header("Authorization", t));

        // Twenty through, then refused — a button people hold down does not get to rescan the
        // guess table without limit.
        assertThat(fresh).isEqualTo(20);
    }

    @Test
    void syncingAnAssetIsCappedTightlyBecauseItReachesBinance() throws Exception {
        String token = freshAdmin();

        /* An unknown symbol on purpose. The limiter is checked before the service runs, so the
           counter still moves, but nothing calls Binance — a test that proved this limit by
           making ten real requests to a third party would be doing the very thing the limit
           exists to prevent, and would fail in CI whenever that service was unreachable. */
        int accepted = acceptedBefore429(token, 30,
                t -> post("/api/admin/ops/sync/NO-SUCH-PAIR").header("Authorization", t));

        assertThat(accepted).isEqualTo(10);
    }

    /**
     * The blanket ceiling is the backstop, not the working limit: an ordinary burst of admin
     * reads has to pass untouched, or the limit would be a bug rather than a guard.
     */
    @Test
    void anOrdinaryBurstOfAdminReadsIsNotRefused() throws Exception {
        String token = freshAdmin();

        for (int i = 0; i < 40; i++) {
            mockMvc.perform(get("/api/admin/me").header("Authorization", token))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
        }
    }
}

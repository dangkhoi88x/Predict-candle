package com.example.candles.auth;

import com.example.candles.domain.Role;
import com.example.candles.domain.User;
import com.example.candles.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The rules that separate an admin from everyone else. Worth testing rather than eyeballing,
 * because each one fails silently in the direction that matters: a mistake here does not break
 * the app, it opens it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    private User admin;
    private User player;

    @BeforeEach
    void createAccounts() {
        admin = new User(wallet(), "Admin");
        admin.assignRole(Role.ADMIN);
        player = new User(wallet(), "Player");
        userRepository.saveAndFlush(admin);
        userRepository.saveAndFlush(player);
        // No commit: MockMvc dispatches on this thread, so the controller joins the test's
        // transaction and sees these rows. Committing them instead would leave a pair of
        // accounts behind in the database after every run.
    }

    private static String wallet() {
        return "0x" + UUID.randomUUID().toString().replace("-", "");
    }

    @Test
    void anonymousCallerIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void ordinaryPlayerIsForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/me").header("Authorization", bearer(player)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void adminIsAllowedAndToldTheirRole() throws Exception {
        mockMvc.perform(get("/api/admin/me").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.walletAddress").value(admin.getWalletAddress()));
    }

    /**
     * The reason AdminAccess re-reads the database. A token is a snapshot: this one claims a
     * role its account does not have, which is what a token minted before a demotion looks
     * like. The URL rule lets it through — it only sees the claim — and the database check
     * is what stops it.
     */
    @Test
    void tokenClaimingAdminForAnAccountThatIsNotOneIsRejected() throws Exception {
        String forged = jwtService.createAccessToken(adminClaimingCopyOf(player));

        mockMvc.perform(get("/api/admin/me").header("Authorization", "Bearer " + forged))
                .andExpect(status().isForbidden());
    }

    @Test
    void mediaWritesRequireTheRoleToo() throws Exception {
        mockMvc.perform(delete("/api/media/images").param("publicId", "candles/blog/whatever"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/media/images").param("publicId", "candles/blog/whatever")
                        .header("Authorization", bearer(player)))
                .andExpect(status().isForbidden());
    }

    @Test
    void changingRoleEndsExistingSessions() {
        int before = player.getTokenVersion();

        assertThat(player.assignRole(Role.ADMIN)).isTrue();
        assertThat(player.getTokenVersion()).isGreaterThan(before);

        // Assigning the role it already holds is not a change, so it must not churn sessions.
        assertThat(player.assignRole(Role.ADMIN)).isFalse();
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.createAccessToken(user);
    }

    /** A stand-in for the same account before a demotion: same id, stale role. */
    private User adminClaimingCopyOf(User user) {
        User copy = new User(user.getWalletAddress(), user.getDisplayName());
        setId(copy, user.getId());
        copy.assignRole(Role.ADMIN);
        return copy;
    }

    private static void setId(User user, Long id) {
        try {
            var field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}

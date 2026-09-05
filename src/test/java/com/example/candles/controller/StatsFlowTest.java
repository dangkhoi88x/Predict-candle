package com.example.candles.controller;

import com.example.candles.entity.Role;
import com.example.candles.entity.User;
import com.example.candles.repository.UserRepository;
import com.example.candles.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A signed-in player's own profile numbers, and the one way this used to break: an access
 * token stays valid for its whole TTL even if the account it names is deleted in the
 * meantime — an admin removing a player, most plainly. {@code /api/stats/me} used to read
 * that user with a bare {@code findById(userId).orElseThrow()}, which threw an unmapped
 * {@link java.util.NoSuchElementException} and came back as a 500. It is not a server bug —
 * the account is really gone — so it belongs with every other "this session no longer names
 * a real account" case, a 401.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StatsFlowTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private JwtService jwt;

    private User player() {
        User user = new User("0x" + UUID.randomUUID().toString().replace("-", ""), "P");
        user.assignRole(Role.USER);
        return users.saveAndFlush(user);
    }

    @Test
    void aFreshPlayerReadsAllZeroesRatherThanAnError() throws Exception {
        User player = player();
        String bearer = "Bearer " + jwt.createAccessToken(player);

        mockMvc.perform(get("/api/stats/me").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.correct").value(0))
                .andExpect(jsonPath("$.score").value(0));
    }

    @Test
    void aTokenForASinceDeletedAccountIsAnInvalidSessionNotAServerError() throws Exception {
        User player = player();
        String bearer = "Bearer " + jwt.createAccessToken(player);

        users.delete(player);
        users.flush();

        mockMvc.perform(get("/api/stats/me").header("Authorization", bearer))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void importingLegacyStatsForASinceDeletedAccountIsAlsoAnInvalidSession() throws Exception {
        User player = player();
        String bearer = "Bearer " + jwt.createAccessToken(player);

        users.delete(player);
        users.flush();

        mockMvc.perform(post("/api/stats/me/legacy")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"total\":10,\"correct\":8,\"score\":100,\"bestStreak\":5}"))
                .andExpect(status().isUnauthorized());

        assertThat(users.findAll()).noneMatch(u -> u.getId().equals(player.getId()));
    }
}

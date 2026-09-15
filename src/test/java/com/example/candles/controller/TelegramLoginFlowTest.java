package com.example.candles.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;

import com.example.candles.entity.Role;
import com.example.candles.entity.User;
import com.example.candles.repository.UserRepository;
import com.example.candles.security.TelegramInitDataFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Signing in from inside Telegram, over HTTP: the account it makes, and the ones it must not. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "candles.telegram.bot-token=" + TelegramLoginFlowTest.BOT_TOKEN,
        "candles.telegram.bot-username=candle_guess_bot",
        "candles.telegram.app-name=play",
})
class TelegramLoginFlowTest {

    static final String BOT_TOKEN = "7000000001:AAflowTestTokenOnly_abcdefghijklmnop";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;

    private final ObjectMapper mapper = new ObjectMapper();

    private MvcResult login(String initData) throws Exception {
        return mockMvc.perform(post("/api/auth/telegram")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("initData", initData))))
                .andReturn();
    }

    @Test
    void aLaunchBecomesAnAccountAndTheNextLaunchFindsTheSameOne() throws Exception {
        long telegramId = 900_000_000L + (System.nanoTime() % 1_000_000);
        MvcResult first = login(TelegramInitDataFixture.signed(BOT_TOKEN, telegramId, "lan_trader", Instant.now()));

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(first.getResponse().getHeader("Set-Cookie")).contains("HttpOnly");
        JsonNode body = mapper.readTree(first.getResponse().getContentAsString());
        assertThat(body.path("displayName").asString()).isEqualTo("@lan_trader");
        assertThat(body.path("role").asString()).isEqualTo("USER");

        User user = users.findByWalletAddress("tg:" + telegramId).orElseThrow();
        assertThat(user.getRole()).isEqualTo(Role.USER);

        MvcResult again = login(TelegramInitDataFixture.signed(BOT_TOKEN, telegramId, "renamed", Instant.now()));
        assertThat(mapper.readTree(again.getResponse().getContentAsString()).path("userId").asLong())
                .isEqualTo(user.getId());

        String accessToken = body.path("accessToken").asString();
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.getId()));
    }

    @Test
    void aTamperedLaunchIsRefusedAndMakesNoAccount() throws Exception {
        long before = users.count();
        String signed = TelegramInitDataFixture.signed(BOT_TOKEN, 555L, "lan", Instant.now());

        MvcResult result = login(signed.replace("lan", "admin"));

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        // A player in Telegram has no wallet; the refusal must not tell them about one.
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("message").asString())
                .contains("Telegram")
                .doesNotContain("ví");
        assertThat(users.count()).isEqualTo(before);
    }

    @Test
    void theSiteConfigTellsThePageTelegramIsOn() throws Exception {
        mockMvc.perform(get("/api/site-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.telegram.login").value(true))
                .andExpect(jsonPath("$.telegram.appLink").value("https://t.me/candle_guess_bot/play"));
    }

    @Test
    void telegramWebMayFrameThePageAndNobodyElse() throws Exception {
        mockMvc.perform(get("/api/site-config"))
                .andExpect(header().doesNotExist("X-Frame-Options"))
                .andExpect(header().string("Content-Security-Policy", "frame-ancestors 'self' https://web.telegram.org"));
    }
}

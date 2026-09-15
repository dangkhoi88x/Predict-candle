package com.example.candles.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.example.candles.CandleFixture;
import com.example.candles.client.TelegramBotClient;
import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.DailyRound;
import com.example.candles.entity.Asset;
import com.example.candles.entity.Role;
import com.example.candles.entity.User;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.CandleRepository;
import com.example.candles.repository.UserRepository;
import com.example.candles.security.JwtService;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The reminders card's three endpoints, and that only an admin reaches them. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "candles.telegram.bot-token=7000000001:AAbroadcastTestTokenOnly_abcdefghij",
        "candles.telegram.bot-username=candle_guess_bot",
        "candles.telegram.app-name=play",
        "candles.telegram.daily-chat-ids=-1001, -1002",
        "candles.telegram.morning-cron=-",
        "candles.telegram.evening-cron=-",
})
class AdminTelegramTest {

    /** 21:00 in Vietnam on a day the candle fixture can build a daily for. */
    private static final Instant NOW = Instant.parse("2026-03-15T14:00:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private AssetRepository assets;
    @Autowired private CandleRepository candles;
    @Autowired private UserRepository users;
    @Autowired private CandlesProperties properties;
    @Autowired private JwtService jwt;
    @MockitoBean private TelegramBotClient bot;
    @MockitoBean private Clock clock;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(bot.configured()).thenReturn(true);
        for (Asset asset : assets.findAllByOrderByPositionAscSymbolAsc()) {
            CandleFixture.seedIfEmpty(candles, asset, properties.timeframe());
        }
    }

    private String bearer(Role role) {
        User user = new User("0x" + UUID.randomUUID().toString().replace("-", ""), "Người thử");
        user.assignRole(role);
        return "Bearer " + jwt.createAccessToken(users.saveAndFlush(user));
    }

    @Test
    void theCardShowsTodaysTwoMessagesAndThenWhatWasSent() throws Exception {
        String admin = bearer(Role.ADMIN);
        long number = DailyRound.forDay(LocalDate.of(2026, 3, 15)).number();

        mockMvc.perform(get("/api/admin/telegram").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.chatIds[1]").value(-1002))
                .andExpect(jsonPath("$.roundNumber").value(number))
                .andExpect(jsonPath("$.morning.html").value(containsString("Thử thách #" + number)))
                .andExpect(jsonPath("$.evening.html").value(containsString("Còn 10 tiếng")))
                .andExpect(jsonPath("$.morning.buttonUrl").value("https://t.me/candle_guess_bot/play?startapp=daily"))
                .andExpect(jsonPath("$.sent").isEmpty());

        mockMvc.perform(post("/api/admin/telegram/send?kind=MORNING").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outcome").value("SENT"))
                .andExpect(jsonPath("$[1].outcome").value("SENT"));
        mockMvc.perform(post("/api/admin/telegram/send?kind=MORNING").header("Authorization", admin))
                .andExpect(jsonPath("$[0].outcome").value("ALREADY_SENT"));

        verify(bot, times(1)).sendMessage(eq(-1001L), anyString(), anyString(), anyString());
        mockMvc.perform(get("/api/admin/telegram").header("Authorization", admin))
                .andExpect(jsonPath("$.sent.length()").value(2))
                .andExpect(jsonPath("$.sent[0].kind").value("MORNING"));
    }

    @Test
    void theChatsTheBotHasHeardFromAreListed() throws Exception {
        when(bot.recentChats()).thenReturn(List.of(new TelegramBotClient.Chat(-1001234567890L, "supergroup", "Crypto VN")));

        mockMvc.perform(get("/api/admin/telegram/chats").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(-1001234567890L))
                .andExpect(jsonPath("$[0].title").value("Crypto VN"));
    }

    @Test
    void aTelegramFailureIsAnAnswerNotAServerError() throws Exception {
        when(bot.recentChats()).thenThrow(new TelegramBotClient.TelegramApiException("Unauthorized"));

        mockMvc.perform(get("/api/admin/telegram/chats").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Unauthorized")));
    }

    @Test
    void onlyAnAdminCanSeeOrSend() throws Exception {
        String user = bearer(Role.USER);
        mockMvc.perform(get("/api/admin/telegram").header("Authorization", user)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/telegram/chats").header("Authorization", user)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/telegram/send?kind=EVENING").header("Authorization", user)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/telegram/send?kind=EVENING")).andExpect(status().is4xxClientError());

        verify(bot, never()).sendMessage(eq(-1001L), anyString(), anyString(), anyString());
    }
}

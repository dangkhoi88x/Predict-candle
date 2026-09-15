package com.example.candles.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.example.candles.CandleFixture;
import com.example.candles.client.TelegramBotClient;
import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.DailyRound;
import com.example.candles.domain.RoundSelection;
import com.example.candles.entity.Asset;
import com.example.candles.entity.Direction;
import com.example.candles.entity.GuessMode;
import com.example.candles.entity.GuessResult;
import com.example.candles.entity.Role;
import com.example.candles.entity.TelegramBroadcast;
import com.example.candles.entity.User;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.CandleRepository;
import com.example.candles.repository.GuessResultRepository;
import com.example.candles.repository.TelegramBroadcastRepository;
import com.example.candles.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The group reminders: what they say, who they leave out, and that each goes out once. */
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "candles.telegram.bot-token=7000000001:AAbroadcastTestTokenOnly_abcdefghij",
        "candles.telegram.bot-username=candle_guess_bot",
        "candles.telegram.app-name=play",
        "candles.telegram.daily-chat-ids=-1001, -1002",
        // Never fire from the clock while the suite runs; the test calls broadcast() itself.
        "candles.telegram.morning-cron=-",
        "candles.telegram.evening-cron=-",
})
class TelegramDailyBroadcastTest {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 15);
    /** 21:00 in Vietnam: ten hours before the round closes at UTC midnight. */
    private static final Instant EVENING = Instant.parse("2026-03-15T14:00:00Z");

    @Autowired private TelegramDailyBroadcastService service;
    @Autowired private RoundSelectionService rounds;
    @Autowired private AssetRepository assets;
    @Autowired private CandleRepository candles;
    @Autowired private GuessResultRepository guessResults;
    @Autowired private TelegramBroadcastRepository broadcasts;
    @Autowired private UserRepository users;
    @Autowired private CandlesProperties properties;
    @MockitoBean private TelegramBotClient bot;

    @BeforeEach
    void setUp() {
        when(bot.configured()).thenReturn(true);
        for (Asset asset : assets.findAllByOrderByPositionAscSymbolAsc()) {
            CandleFixture.seedIfEmpty(candles, asset, properties.timeframe());
        }
    }

    private User player(String name, Role role) {
        User user = new User("0x" + UUID.randomUUID().toString().replace("-", ""), name);
        user.assignRole(role);
        return users.saveAndFlush(user);
    }

    /** Plays today's daily: {@code guesses} calls, the first {@code correct} of them right. */
    private void play(User user, int guesses, int correct) {
        RoundSelection round = rounds.selectDailyRound(DAY);
        for (int n = 1; n <= guesses; n++) {
            Direction actual = Direction.LONG;
            Direction guessed = n <= correct ? Direction.LONG : Direction.SHORT;
            guessResults.saveAndFlush(new GuessResult(user, round.asset(), round.timeframe(),
                    round.startIndex(), n, guessed, actual, GuessMode.DAILY));
        }
    }

    @Test
    void theMorningMessageGoesToEveryChatOnceAndGivesNothingAway() {
        List<TelegramDailyBroadcastService.ChatResult> first = service.broadcast(TelegramBroadcast.Kind.MORNING, DAY, EVENING);
        List<TelegramDailyBroadcastService.ChatResult> again = service.broadcast(TelegramBroadcast.Kind.MORNING, DAY, EVENING);

        assertThat(first).extracting(TelegramDailyBroadcastService.ChatResult::outcome)
                .containsExactly(TelegramDailyBroadcastService.Outcome.SENT, TelegramDailyBroadcastService.Outcome.SENT);
        assertThat(again).extracting(TelegramDailyBroadcastService.ChatResult::outcome)
                .containsOnly(TelegramDailyBroadcastService.Outcome.ALREADY_SENT);

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(bot).sendMessage(eq(-1001L), html.capture(), anyString(), eq("https://t.me/candle_guess_bot/play?startapp=daily"));
        verify(bot, times(1)).sendMessage(eq(-1002L), anyString(), anyString(), anyString());

        long number = DailyRound.forDay(DAY).number();
        String symbol = rounds.selectDailyRound(DAY).asset().getSymbol();
        assertThat(html.getValue()).contains("Thử thách #" + number).doesNotContain(symbol).doesNotContain("2026");
    }

    @Test
    void theEveningStandingsRankFinishersAndLeaveOutAdminsAndTheUnfinished() {
        play(player("Lan", Role.USER), 5, 4);
        play(player("Bình", Role.USER), 5, 5);
        play(player("<b>Cá</b>", Role.USER), 5, 4);   // same score as Lan, finished later
        play(player("Quản trị", Role.ADMIN), 5, 5);
        play(player("Dở dang", Role.USER), 3, 3);
        play(player("Người thứ tư", Role.USER), 5, 1);

        service.broadcast(TelegramBroadcast.Kind.EVENING, DAY, EVENING);

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(bot).sendMessage(eq(-1001L), html.capture(), anyString(), anyString());
        assertThat(html.getValue())
                .contains("🥇 Bình: 5/5\n🥈 Lan: 4/5\n🥉 &lt;b&gt;Cá&lt;/b&gt;: 4/5\n")
                .contains("4 người đã chơi xong. Còn 10 tiếng")
                .doesNotContain("Quản trị")
                .doesNotContain("Dở dang")
                .doesNotContain("Người thứ tư");
    }

    @Test
    void anEveningNobodyHasFinishedStillInvitesTheGroup() {
        service.broadcast(TelegramBroadcast.Kind.EVENING, DAY, EVENING);

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(bot).sendMessage(eq(-1001L), html.capture(), eq("Chơi ngay"), anyString());
        assertThat(html.getValue()).contains("Chưa ai chơi xong").contains("Còn 10 tiếng");
    }

    @Test
    void aFailedSendGivesUpItsClaimSoTheNextTryPostsIt() {
        doThrow(new TelegramBotClient.TelegramApiException("Forbidden: bot was kicked from the group chat"))
                .when(bot).sendMessage(eq(-1002L), anyString(), anyString(), anyString());

        List<TelegramDailyBroadcastService.ChatResult> results = service.broadcast(TelegramBroadcast.Kind.MORNING, DAY, EVENING);

        assertThat(results.get(1).outcome()).isEqualTo(TelegramDailyBroadcastService.Outcome.FAILED);
        assertThat(results.get(1).error()).contains("kicked");
        assertThat(broadcasts.existsByChatIdAndKindAndDay(-1001L, TelegramBroadcast.Kind.MORNING, DAY)).isTrue();
        assertThat(broadcasts.existsByChatIdAndKindAndDay(-1002L, TelegramBroadcast.Kind.MORNING, DAY)).isFalse();

        service.broadcast(TelegramBroadcast.Kind.MORNING, DAY, EVENING);

        verify(bot, times(1)).sendMessage(eq(-1001L), anyString(), anyString(), anyString());
        verify(bot, times(2)).sendMessage(eq(-1002L), anyString(), anyString(), anyString());
    }

    @Test
    void morningAndEveningAreSeparateMessagesOfTheSameDay() {
        service.broadcast(TelegramBroadcast.Kind.MORNING, DAY, EVENING);
        service.broadcast(TelegramBroadcast.Kind.EVENING, DAY, EVENING);
        service.broadcast(TelegramBroadcast.Kind.MORNING, DAY.plusDays(1), EVENING.plusSeconds(86_400));

        verify(bot, times(3)).sendMessage(eq(-1001L), anyString(), anyString(), anyString());
    }

    @Test
    void chatIdsAreReadForgivingly() {
        assertThat(TelegramDailyBroadcastService.parseChatIds(" -1001, abc,,-1001, 42 ")).containsExactly(-1001L, 42L);
        assertThat(TelegramDailyBroadcastService.parseChatIds("")).isEmpty();
        assertThat(TelegramDailyBroadcastService.escape("a < b & c > d")).isEqualTo("a &lt; b &amp; c &gt; d");
        verify(bot, never()).sendMessage(anyLong(), anyString(), anyString(), anyString());
    }
}

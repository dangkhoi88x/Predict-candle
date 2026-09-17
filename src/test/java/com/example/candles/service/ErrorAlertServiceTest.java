package com.example.candles.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import com.example.candles.client.TelegramBotClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The half of error monitoring that reaches somebody.
 *
 * Two rules carry the whole design and both are about a phone staying usable during an outage: only
 * a failure that started its own row is announced, and at most one message per cooldown — with what
 * was held back counted in the next one rather than dropped silently.
 *
 * Sending runs inline here ({@code Runnable::run}); in the app it is a daemon thread, because an
 * alert must not put an HTTP call to Telegram in front of the request that failed.
 */
class ErrorAlertServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-17T09:00:00Z");
    private static final String CHAT = "555000111";

    private final TelegramBotClient bot = mock(TelegramBotClient.class);
    private final MovableClock clock = new MovableClock(T0);

    private ErrorAlertService alerts(String chatId) {
        when(bot.configured()).thenReturn(true);
        return new ErrorAlertService(bot, clock, Runnable::run, chatId, Duration.ofMinutes(15),
                "https://candles-oj1q.onrender.com/");
    }

    @Test
    void aNewFailureIsSentWithWhereItHappenedAndAWayToTheOpsPane() {
        alerts(CHAT).newFailure("sync", "BTCUSDT", "java.net.UnknownHostException: api.binance.com");

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(bot).sendMessage(eq(555000111L), text.capture(), eq("Mở trang vận hành"),
                eq("https://candles-oj1q.onrender.com/admin.html#ops"));
        assertThat(text.getValue())
                .contains("Lỗi mới")
                .contains("sync · BTCUSDT")
                .contains("UnknownHostException");
    }

    /** A stack trace is full of angle brackets, and Telegram's HTML mode would refuse the message. */
    @Test
    void theMessageIsEscapedForTelegramsHtml() {
        alerts(CHAT).newFailure("server", "GET /x", "IllegalState: <html> & \"quoted\"");

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(bot).sendMessage(anyLong(), text.capture(), anyString(), anyString());
        assertThat(text.getValue()).contains("&lt;html&gt;").contains("&amp;").doesNotContain("<html>");
    }

    @Test
    void oneMessagePerCooldownAndWhatWasHeldBackIsNamedInTheNextOne() {
        ErrorAlertService alerts = alerts(CHAT);

        alerts.newFailure("sync", "BTCUSDT", "first");
        clock.advance(Duration.ofMinutes(5));
        alerts.newFailure("sync", "ETHUSDT", "second");
        clock.advance(Duration.ofMinutes(5));
        alerts.newFailure("sync", "SOLUSDT", "third");

        verify(bot).sendMessage(anyLong(), anyString(), anyString(), anyString());

        clock.advance(Duration.ofMinutes(11));
        alerts.newFailure("upstream", "GET /api/live/round", "fourth");

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(bot, org.mockito.Mockito.times(2)).sendMessage(anyLong(), text.capture(), anyString(), anyString());
        assertThat(text.getAllValues().getLast())
                .contains("fourth")
                .contains("Còn 2 lỗi khác");
    }

    @Test
    void withoutAChatIdOrWithoutABotNothingIsSent() {
        alerts("").newFailure("sync", "BTCUSDT", "boom");

        ErrorAlertService noBot = new ErrorAlertService(bot, clock, Runnable::run, CHAT,
                Duration.ofMinutes(15), "https://example.com");
        when(bot.configured()).thenReturn(false);
        noBot.newFailure("sync", "BTCUSDT", "boom");

        verify(bot, never()).sendMessage(anyLong(), anyString(), anyString(), anyString());
        assertThat(noBot.enabled()).isFalse();
    }

    /** The row is already recorded; a failed alert must not become a second failure on the pane. */
    @Test
    void aTelegramFailureIsSwallowed() {
        doThrow(new TelegramBotClient.TelegramApiException("Forbidden: bot was blocked by the user"))
                .when(bot).sendMessage(anyLong(), anyString(), anyString(), anyString());

        assertThatCode(() -> alerts(CHAT).newFailure("sync", "BTCUSDT", "boom")).doesNotThrowAnyException();
    }

    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

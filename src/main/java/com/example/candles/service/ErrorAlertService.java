package com.example.candles.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executor;

import com.example.candles.client.TelegramBotClient;

/**
 * Tells somebody when a new kind of failure starts, through the bot this app already runs.
 *
 * The ops pane is a page you have to think of opening; this is the half that reaches you. It is the
 * cheap version of an error tracker on purpose — the alternative was a third-party account for a
 * demo whose whole error history is a handful of exchange bans, and the bot is already configured,
 * already authenticated, and already where the owner reads about this site.
 *
 * <b>Only a new episode.</b> {@link AppErrorStore#record} says whether the row was new or folded
 * into the one on top, and a repeat of a failure already on the pane is not news — an exchange ban
 * raises the same error on every poll, which would be a message a second.
 *
 * <b>And at most one message per {@code candles.errors.alert-cooldown}.</b> A night where every
 * poll fails a different way is exactly when a phone must not be unusable; the messages that were
 * held back are counted and named in the next one, so nothing is silently dropped.
 *
 * Off unless {@code TELEGRAM_ALERT_CHAT_ID} names a chat — and off with no bot token at all, the
 * same as every other Telegram feature. Sending happens on its own thread: an alert must not put
 * an HTTP call to Telegram in front of the request that failed.
 */
@Service
public class ErrorAlertService {

    private static final Logger log = LoggerFactory.getLogger(ErrorAlertService.class);

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm dd/MM").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private final TelegramBotClient bot;
    private final Clock clock;
    private final Executor executor;
    private final String chatId;
    private final Duration cooldown;
    private final String siteUrl;

    private Instant lastSentAt;
    private int suppressed;

    @Autowired
    public ErrorAlertService(TelegramBotClient bot, Clock clock,
                             @Value("${candles.errors.alert-chat-id:}") String chatId,
                             @Value("${candles.errors.alert-cooldown:PT15M}") Duration cooldown,
                             @Value("${candles.site-url:https://candles-oj1q.onrender.com}") String siteUrl) {
        this(bot, clock, daemonExecutor(), chatId, cooldown, siteUrl);
    }

    /** The executor is an argument only so a test can run the send inline and assert on it. */
    ErrorAlertService(TelegramBotClient bot, Clock clock, Executor executor,
                      String chatId, Duration cooldown, String siteUrl) {
        this.bot = bot;
        this.clock = clock;
        this.executor = executor;
        this.chatId = chatId == null ? "" : chatId.trim();
        this.cooldown = cooldown;
        this.siteUrl = siteUrl.replaceAll("/+$", "");
    }

    /**
     * One thread, and a queue that drops rather than grows: an alert is worth less than the
     * request behind it, and a Telegram outage must not hold a thousand of them in memory.
     */
    private static Executor daemonExecutor() {
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(
                1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(32),
                runnable -> {
                    Thread thread = new Thread(runnable, "error-alerts");
                    thread.setDaemon(true);
                    return thread;
                },
                new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
        return pool;
    }

    public boolean enabled() {
        return bot.configured() && !chatId.isEmpty();
    }

    /** Called with a failure that has just started its own row. Never throws. */
    public void newFailure(String source, String where, String summary) {
        if (!enabled()) return;
        String message;
        synchronized (this) {
            Instant now = clock.instant();
            if (lastSentAt != null && lastSentAt.isAfter(now.minus(cooldown))) {
                suppressed++;
                return;
            }
            message = compose(source, where, summary, now, suppressed);
            lastSentAt = now;
            suppressed = 0;
        }
        executor.execute(() -> send(message));
    }

    private String compose(String source, String where, String summary, Instant at, int held) {
        StringBuilder text = new StringBuilder("⚠️ <b>Lỗi mới</b> · ").append(TIME.format(at)).append('\n')
                .append(escape(source)).append(" · ").append(escape(where)).append('\n')
                .append("<code>").append(escape(summary)).append("</code>");
        if (held > 0) {
            text.append("\n\nCòn ").append(held).append(" lỗi khác trong lúc chờ gửi — mở trang vận hành để xem đủ.");
        }
        return text.toString();
    }

    private void send(String message) {
        try {
            bot.sendMessage(Long.parseLong(chatId), message, "Mở trang vận hành", siteUrl + "/admin.html#ops");
        } catch (RuntimeException e) {
            // An alert that cannot be delivered is not worth a second error on the pane it came
            // from; the row is already recorded, which is the part that had to survive.
            log.warn("Could not send an error alert: {}", e.toString());
        }
    }

    /** Telegram's HTML mode understands only these three, and a stack trace contains all of them. */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

package com.example.candles.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import com.example.candles.client.TelegramBotClient;
import com.example.candles.domain.DailyRound;
import com.example.candles.dto.response.AdminTelegramStatus;
import com.example.candles.entity.TelegramBroadcast;
import com.example.candles.security.AdminAccess;
import com.example.candles.service.TelegramDailyBroadcastService;

/**
 * The group reminders, from the admin page: see what today's two messages say, find a group's
 * chat id, and post one now.
 *
 * Nothing here changes where the bot posts — that is {@code TELEGRAM_DAILY_CHAT_IDS}, for the
 * reason roles live in configuration: a group agreeing to reminders is a decision to write down,
 * not a toggle on a screen. Sending now goes through the same claims as the schedule, so it
 * cannot double a message the schedule already posted — and a message sent now is the day's
 * message, which the schedule will then skip.
 */
@RestController
@RequestMapping("/api/admin/telegram")
public class AdminTelegramController {

    private final TelegramDailyBroadcastService broadcasts;
    private final TelegramBotClient bot;
    private final AdminAccess adminAccess;
    private final Clock clock;
    private final String morningCron;
    private final String eveningCron;

    public AdminTelegramController(TelegramDailyBroadcastService broadcasts, TelegramBotClient bot,
                                   AdminAccess adminAccess, Clock clock,
                                   @Value("${candles.telegram.morning-cron:0 0 8 * * *}") String morningCron,
                                   @Value("${candles.telegram.evening-cron:0 0 21 * * *}") String eveningCron) {
        this.broadcasts = broadcasts;
        this.bot = bot;
        this.adminAccess = adminAccess;
        this.clock = clock;
        this.morningCron = morningCron;
        this.eveningCron = eveningCron;
    }

    @GetMapping
    public AdminTelegramStatus status() {
        Instant now = clock.instant();
        LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
        return new AdminTelegramStatus(
                broadcasts.enabled(),
                broadcasts.botConfigured(),
                broadcasts.appLink(),
                broadcasts.chatIds(),
                morningCron,
                eveningCron,
                day,
                DailyRound.forDay(day).number(),
                preview(TelegramBroadcast.Kind.MORNING, day, now),
                preview(TelegramBroadcast.Kind.EVENING, day, now),
                broadcasts.claimsOn(day).stream()
                        .map(b -> new AdminTelegramStatus.Sent(b.getChatId(), b.getKind().name(), b.getSentAt()))
                        .toList());
    }

    private AdminTelegramStatus.Preview preview(TelegramBroadcast.Kind kind, LocalDate day, Instant now) {
        try {
            TelegramDailyBroadcastService.Message m = broadcasts.compose(kind, day, now);
            return new AdminTelegramStatus.Preview(m.html(), m.buttonText(), m.buttonUrl(), null);
        } catch (RuntimeException e) {
            return new AdminTelegramStatus.Preview(null, null, null, e.getMessage());
        }
    }

    /** Chats the bot has heard from in the last day, to copy an id out of. */
    @GetMapping("/chats")
    public List<TelegramBotClient.Chat> chats() {
        if (!bot.configured()) throw new IllegalStateException("Chưa cấu hình TELEGRAM_BOT_TOKEN.");
        try {
            return bot.recentChats();
        } catch (TelegramBotClient.TelegramApiException e) {
            throw new IllegalStateException("Telegram trả lỗi: " + e.getMessage());
        }
    }

    @PostMapping("/send")
    public List<TelegramDailyBroadcastService.ChatResult> send(@RequestParam TelegramBroadcast.Kind kind) {
        adminAccess.requireAdmin();
        if (!broadcasts.enabled()) {
            throw new IllegalStateException("Bot chưa gửi được: cần TELEGRAM_BOT_TOKEN, TELEGRAM_DAILY_CHAT_IDS, "
                    + "TELEGRAM_BOT_USERNAME và TELEGRAM_APP_NAME.");
        }
        Instant now = clock.instant();
        return broadcasts.broadcast(kind, LocalDate.ofInstant(now, ZoneOffset.UTC), now);
    }
}

package com.example.candles.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import com.example.candles.entity.TelegramBroadcast;

/**
 * When the group announcements go out — Vietnam time, because that is when the group is awake.
 * 08:00 is an hour after the day's round opens at UTC midnight; 21:00 still leaves ten hours to
 * play. Either cron can be set to {@code -} to switch that message off.
 *
 * The day announced is the UTC day of the moment the job runs, which is the round the site is
 * serving at that moment — at both times, the Vietnam date and the UTC date agree.
 *
 * A job that fires while the instance is asleep does not run at all: Render's free plan stops an
 * idle service, which is what the keep-awake cron in DEPLOY_PLAN §4.3 is for.
 */
@Component
public class TelegramDailyScheduler {

    private final TelegramDailyBroadcastService broadcasts;
    private final Clock clock;

    public TelegramDailyScheduler(TelegramDailyBroadcastService broadcasts, Clock clock) {
        this.broadcasts = broadcasts;
        this.clock = clock;
    }

    @Scheduled(cron = "${candles.telegram.morning-cron:0 0 8 * * *}", zone = "Asia/Ho_Chi_Minh")
    public void morning() {
        run(TelegramBroadcast.Kind.MORNING);
    }

    @Scheduled(cron = "${candles.telegram.evening-cron:0 0 21 * * *}", zone = "Asia/Ho_Chi_Minh")
    public void evening() {
        run(TelegramBroadcast.Kind.EVENING);
    }

    private void run(TelegramBroadcast.Kind kind) {
        if (!broadcasts.enabled()) return;
        Instant now = clock.instant();
        broadcasts.broadcast(kind, LocalDate.ofInstant(now, ZoneOffset.UTC), now);
    }
}

package com.example.candles.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The reminders card on the admin's challenges pane: whether the bot can post, where, when, what
 * it would say today, and what has already gone out.
 *
 * @param enabled     token, chat ids and app link all present — the scheduler runs only then
 * @param morning     today's morning message as it would be sent now
 * @param evening     today's evening message as it would be sent now — the standings move until midnight
 * @param sent        today's claims: a message posted, or being posted, to that chat
 */
public record AdminTelegramStatus(
        boolean enabled,
        boolean botConfigured,
        String appLink,
        List<Long> chatIds,
        String morningCron,
        String eveningCron,
        LocalDate day,
        long roundNumber,
        Preview morning,
        Preview evening,
        List<Sent> sent
) {

    /** {@code problem} is set instead of the text when the message cannot be composed. */
    public record Preview(String html, String buttonText, String buttonUrl, String problem) {
    }

    public record Sent(long chatId, String kind, Instant sentAt) {
    }
}

package com.example.candles.dto.response;

import java.time.Instant;

/**
 * One account as the admin list shows it.
 *
 * {@code role} is displayed and not editable: it is reconciled from candles.admin.wallets at
 * every startup, so a change made here would be undone on the next restart. Showing it with
 * no control beside it is the honest rendering of that.
 *
 * {@code login} is WALLET or TELEGRAM, read off the account key ({@code tg:<id>} for Telegram), so
 * the page never has to know that prefix.
 */
public record PlayerSummary(
        Long id,
        String walletAddress,
        String displayName,
        String role,
        long guesses,
        long correct,
        boolean legacyImported,
        Instant createdAt,
        Instant lastPlayedAt,
        String login
) {

    public static String loginOf(String accountKey) {
        return accountKey != null && accountKey.startsWith("tg:") ? "TELEGRAM" : "WALLET";
    }
}

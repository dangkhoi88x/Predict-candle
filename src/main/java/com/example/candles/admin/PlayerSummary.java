package com.example.candles.admin;

import java.time.Instant;

/**
 * One account as the admin list shows it.
 *
 * {@code role} is displayed and not editable: it is reconciled from candles.admin.wallets at
 * every startup, so a change made here would be undone on the next restart. Showing it with
 * no control beside it is the honest rendering of that.
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
        Instant lastPlayedAt
) {
}

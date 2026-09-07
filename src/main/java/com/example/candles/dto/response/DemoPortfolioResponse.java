package com.example.candles.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A paper account as the terminal draws it.
 *
 * Two profit numbers, and confusing them is a visible bug. {@code realisedPnl} is money that has
 * actually come back from closed trades; {@code unrealisedPnl} is what open positions are worth
 * on paper right now and could be gone by the next tick. {@code equity} is cash plus what is
 * held at live prices — the one number that answers "how am I doing".
 *
 * {@code startingBalance} travels with it so the client can show a return without hard-coding
 * the figure the server handed out, which would silently lie the moment the config changed.
 */
public record DemoPortfolioResponse(
        BigDecimal cash,
        BigDecimal equity,
        BigDecimal startingBalance,
        BigDecimal realisedPnl,
        BigDecimal unrealisedPnl,
        int feeBps,
        int resets,
        Instant openedAt,
        List<Position> positions,
        List<Market> markets,
        List<Fill> recent
) {
    /**
     * One open holding. {@code price} is null when the feed had nothing for it, and then
     * {@code value} and {@code unrealisedPnl} are null too rather than zero — an unknown price
     * is not a position worth nothing.
     */
    public record Position(String symbol, String name, BigDecimal quantity, BigDecimal averageCost,
                           BigDecimal price, BigDecimal value, BigDecimal unrealisedPnl) {
    }

    /** A tradable market and its live price, so the terminal needs no second call to quote. */
    public record Market(String symbol, String name, BigDecimal price) {
    }

    public record Fill(String symbol, String side, BigDecimal quantity, BigDecimal price,
                       BigDecimal fee, Instant at) {
    }
}

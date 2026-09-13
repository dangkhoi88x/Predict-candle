package com.example.candles.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One paper account in full — what it holds, and what it did to get there.
 *
 * Almost the same shape the player's own terminal receives, with one field they cannot have:
 * {@code tradesBeforeReset}. A reset deletes nothing, it moves {@code opened_at} and the fold
 * stops reading before the mark, so an account can hold hundreds of rows while showing three
 * trades. That is invisible from inside the game and the first thing that looks like corruption
 * from outside it.
 */
public record AdminDemoAccount(Long userId, String walletAddress, String displayName,
                                Instant openedAt, int resets, long tradesBeforeReset,
                                BigDecimal startingBalance, int feeBps,
                                BigDecimal cash, BigDecimal realisedPnl,
                                BigDecimal unrealisedPnl, BigDecimal equity,
                                List<Position> positions, List<Fill> trades) {

    /** {@code price} is null when the feed had nothing, and then so are value and P&L. */
    public record Position(String symbol, BigDecimal quantity, BigDecimal averageCost,
                            BigDecimal price, BigDecimal value, BigDecimal unrealisedPnl) {
    }

    public record Fill(String symbol, String side, BigDecimal quantity, BigDecimal price,
                        BigDecimal fee, Instant at) {
    }
}

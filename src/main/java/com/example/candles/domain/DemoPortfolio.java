package com.example.candles.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A paper-trading account folded out of its own trade log — cash, what it holds, what each
 * holding cost, and what has been realised so far.
 *
 * Nothing about this is stored. A balance column would be a second source of truth that can
 * drift from the trades that produced it, and every way it drifts (a partial update, a retried
 * request, a crash between two writes) looks to the player like money appearing or vanishing
 * for no trade. Folding it every read costs a scan of one account's trades, which is the same
 * price {@link PlayerScore} already pays for the same reason.
 *
 * Cost basis is average-cost and includes the buy fee, so the number a player is shown as
 * "what this cost me" is what actually left their cash. A sell realises the difference between
 * its net proceeds and the share of basis it retires; the remaining basis shrinks proportionally
 * rather than being recomputed, so selling half a position cannot move the average price of the
 * half still held.
 *
 * Deliberately a pure function over the ordered rows: no repository, no entities. The awkward
 * part of paper trading is the accounting, and this is the shape that makes it testable.
 */
public record DemoPortfolio(BigDecimal cash, Map<String, Holding> holdings, BigDecimal realisedPnl) {

    private static final MathContext MC = MathContext.DECIMAL64;

    /** {@code quantity} is what is still held; {@code costBasis} is what that quantity cost. */
    public record Holding(BigDecimal quantity, BigDecimal costBasis) {

        /** Null rather than a division by zero when nothing is held — there is no average of nothing. */
        public BigDecimal averageCost() {
            return quantity.signum() == 0 ? null : costBasis.divide(quantity, MC);
        }
    }

    /** One executed trade, in the order it happened. */
    public record Fill(String symbol, boolean buy, BigDecimal quantity, BigDecimal price, BigDecimal fee) {
    }

    public static DemoPortfolio of(BigDecimal startingBalance, List<Fill> fillsInOrder) {
        BigDecimal cash = startingBalance;
        BigDecimal realised = BigDecimal.ZERO;
        Map<String, Holding> holdings = new LinkedHashMap<>();

        for (Fill fill : fillsInOrder) {
            BigDecimal notional = fill.quantity().multiply(fill.price(), MC);
            Holding held = holdings.getOrDefault(fill.symbol(),
                    new Holding(BigDecimal.ZERO, BigDecimal.ZERO));

            if (fill.buy()) {
                cash = cash.subtract(notional).subtract(fill.fee());
                holdings.put(fill.symbol(), new Holding(
                        held.quantity().add(fill.quantity()),
                        // The fee is part of what the position cost, because it is part of what
                        // left the account to open it.
                        held.costBasis().add(notional).add(fill.fee())));
                continue;
            }

            BigDecimal proceeds = notional.subtract(fill.fee());
            cash = cash.add(proceeds);

            // The share of basis this sale retires. Proportional rather than recomputed, so
            // selling part of a position leaves the rest at the average price it always had.
            BigDecimal soldFraction = held.quantity().signum() == 0 ? BigDecimal.ZERO
                    : fill.quantity().divide(held.quantity(), MC);
            BigDecimal retiredBasis = held.costBasis().multiply(soldFraction, MC);

            realised = realised.add(proceeds).subtract(retiredBasis);
            holdings.put(fill.symbol(), new Holding(
                    held.quantity().subtract(fill.quantity()),
                    held.costBasis().subtract(retiredBasis)));
        }

        return new DemoPortfolio(cash, Map.copyOf(holdings), realised);
    }

    /** What is held of one symbol, or zero — callers should not have to handle an absent key. */
    public Holding holding(String symbol) {
        return holdings.getOrDefault(symbol, new Holding(BigDecimal.ZERO, BigDecimal.ZERO));
    }
}

package com.example.candles.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DemoPortfolioTest {

    private static final BigDecimal START = BigDecimal.valueOf(10_000);

    private static DemoPortfolio.Fill buy(String sym, double qty, double price, double fee) {
        return new DemoPortfolio.Fill(sym, true, BigDecimal.valueOf(qty),
                BigDecimal.valueOf(price), BigDecimal.valueOf(fee));
    }

    private static DemoPortfolio.Fill sell(String sym, double qty, double price, double fee) {
        return new DemoPortfolio.Fill(sym, false, BigDecimal.valueOf(qty),
                BigDecimal.valueOf(price), BigDecimal.valueOf(fee));
    }

    private static double d(BigDecimal v) {
        return v.doubleValue();
    }

    @Test
    void anUntradedAccountIsJustItsStartingCash() {
        DemoPortfolio p = DemoPortfolio.of(START, List.of());

        assertThat(d(p.cash())).isEqualTo(10_000);
        assertThat(p.holdings()).isEmpty();
        assertThat(d(p.realisedPnl())).isZero();
        // Asking about something never traded must not blow up.
        assertThat(d(p.holding("BTCUSDT").quantity())).isZero();
        assertThat(p.holding("BTCUSDT").averageCost()).isNull();
    }

    @Test
    void aBuyMovesCashIntoAPositionAndTheFeeGoesWithIt() {
        DemoPortfolio p = DemoPortfolio.of(START, List.of(buy("BTCUSDT", 0.1, 20_000, 2)));

        assertThat(d(p.cash())).isEqualTo(10_000 - 2_000 - 2);
        assertThat(d(p.holding("BTCUSDT").quantity())).isEqualTo(0.1);
        // The fee is part of what the position cost, because it is part of what left the account.
        assertThat(d(p.holding("BTCUSDT").costBasis())).isEqualTo(2_002);
        assertThat(d(p.holding("BTCUSDT").averageCost())).isEqualTo(20_020);
        assertThat(d(p.realisedPnl())).isZero();
    }

    @Test
    void averageCostIsWeightedAcrossBuysRatherThanOverwritten() {
        DemoPortfolio p = DemoPortfolio.of(START, List.of(
                buy("BTCUSDT", 1, 100, 0),
                buy("BTCUSDT", 1, 200, 0)));

        assertThat(d(p.holding("BTCUSDT").quantity())).isEqualTo(2);
        assertThat(d(p.holding("BTCUSDT").averageCost())).isEqualTo(150);
    }

    @Test
    void aProfitableRoundTripRealisesTheDifferenceNetOfBothFees() {
        DemoPortfolio p = DemoPortfolio.of(START, List.of(
                buy("BTCUSDT", 1, 100, 1),
                sell("BTCUSDT", 1, 150, 1)));

        // Paid 101, received 149.
        assertThat(d(p.realisedPnl())).isEqualTo(48);
        assertThat(d(p.cash())).isEqualTo(10_048);
        assertThat(d(p.holding("BTCUSDT").quantity())).isZero();
        assertThat(d(p.holding("BTCUSDT").costBasis())).isZero();
    }

    @Test
    void aLosingRoundTripRealisesANegativeNumber() {
        DemoPortfolio p = DemoPortfolio.of(START, List.of(
                buy("BTCUSDT", 1, 100, 0),
                sell("BTCUSDT", 1, 60, 0)));

        assertThat(d(p.realisedPnl())).isEqualTo(-40);
        assertThat(d(p.cash())).isEqualTo(9_960);
    }

    /**
     * The part that is easy to get wrong: a partial sale must not move the average price of what
     * is still held. Recomputing the basis instead of retiring a proportional share would let a
     * player change their own entry price by selling a sliver.
     */
    @Test
    void sellingHalfLeavesTheRestAtTheSameAverageCost() {
        DemoPortfolio p = DemoPortfolio.of(START, List.of(
                buy("BTCUSDT", 2, 100, 0),
                sell("BTCUSDT", 1, 500, 0)));

        assertThat(d(p.holding("BTCUSDT").quantity())).isEqualTo(1);
        assertThat(d(p.holding("BTCUSDT").averageCost())).isEqualTo(100);
        assertThat(d(p.holding("BTCUSDT").costBasis())).isEqualTo(100);
        assertThat(d(p.realisedPnl())).isEqualTo(400);
    }

    @Test
    void positionsInDifferentSymbolsDoNotBleedIntoEachOther() {
        DemoPortfolio p = DemoPortfolio.of(START, List.of(
                buy("BTCUSDT", 1, 100, 0),
                buy("ETHUSDT", 2, 50, 0),
                sell("BTCUSDT", 1, 120, 0)));

        assertThat(d(p.holding("BTCUSDT").quantity())).isZero();
        assertThat(d(p.holding("ETHUSDT").quantity())).isEqualTo(2);
        assertThat(d(p.holding("ETHUSDT").averageCost())).isEqualTo(50);
        assertThat(d(p.realisedPnl())).isEqualTo(20);
    }

    /**
     * Realised profit is cash that has come back; unrealised profit is not. The fold must not
     * quietly count an open position as money — that is the number that makes a paper account
     * feel like it is winning when it is only holding.
     */
    @Test
    void anOpenPositionRealisesNothingHoweverFarItHasMoved() {
        DemoPortfolio p = DemoPortfolio.of(START, List.of(buy("BTCUSDT", 1, 100, 0)));

        assertThat(d(p.realisedPnl())).isZero();
        assertThat(d(p.cash())).isEqualTo(9_900);
    }
}

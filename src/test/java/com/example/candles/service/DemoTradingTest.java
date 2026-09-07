package com.example.candles.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.candles.client.CandleData;
import com.example.candles.client.PriceDataProvider;
import com.example.candles.config.CandlesProperties;
import com.example.candles.dto.response.DemoPortfolioResponse;
import com.example.candles.entity.Role;
import com.example.candles.entity.User;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Paper trading against a pinned price feed.
 *
 * The feed is mocked because the properties being checked are about money, not about the market:
 * a real feed would make "buying $500 leaves $9,500 minus the fee" depend on what BTC happened to
 * cost while the suite ran. What matters here is that cash cannot be created, a holding cannot be
 * oversold, and the price a trade fills at is the server's rather than the caller's.
 */
@SpringBootTest
@Transactional
class DemoTradingTest {

    @Autowired private DemoTradingService trading;
    @Autowired private UserRepository users;
    @Autowired private AssetRepository assets;
    @Autowired private CandlesProperties properties;
    @Autowired private LivePriceService livePrices;

    @MockitoBean private PriceDataProvider priceDataProvider;

    private String symbol;

    @BeforeEach
    void pinThePrice() {
        symbol = assets.findByEnabledTrueOrderByPositionAscSymbolAsc().getFirst().getSymbol();
        priceIs(100);
    }

    private void priceIs(double close) {
        // The service caches for a couple of seconds, which is right in production and wrong
        // for a test that moves the market between two calls.
        livePrices.evict();
        when(priceDataProvider.fetchCandles(anyString(), anyString(), any(), any()))
                .thenReturn(List.of(new CandleData(Instant.now(), BigDecimal.valueOf(close),
                        BigDecimal.valueOf(close), BigDecimal.valueOf(close),
                        BigDecimal.valueOf(close), BigDecimal.ONE)));
    }

    private Long player() {
        User user = new User("0x" + UUID.randomUUID().toString().replace("-", ""), "T");
        user.assignRole(Role.USER);
        return users.saveAndFlush(user).getId();
    }

    private static double d(BigDecimal v) {
        return v == null ? Double.NaN : v.doubleValue();
    }

    @Test
    void aNewAccountStartsWithTheConfiguredCashAndNothingElse() {
        DemoPortfolioResponse p = trading.portfolio(player());

        assertThat(d(p.cash())).isEqualTo(d(properties.demo().startingBalance()));
        assertThat(d(p.equity())).isEqualTo(d(p.cash()));
        assertThat(p.positions()).isEmpty();
        assertThat(d(p.realisedPnl())).isZero();
        assertThat(p.markets()).isNotEmpty();
    }

    @Test
    void buyingSpendsCashAndOpensAPositionAtTheServersPrice() {
        Long user = player();
        DemoPortfolioResponse p = trading.trade(user, symbol, "BUY", BigDecimal.valueOf(500), null);

        double fee = 500.0 * properties.demo().feeBps() / 10_000;
        assertThat(d(p.cash())).isEqualTo(d(properties.demo().startingBalance()) - 500 - fee);
        assertThat(p.positions()).hasSize(1);
        // $500 at a pinned price of 100 is 5 units — the client never said how many.
        assertThat(d(p.positions().getFirst().quantity())).isEqualTo(5);
        assertThat(d(p.positions().getFirst().price())).isEqualTo(100);
    }

    /** Equity is cash plus holdings at live prices, so a move in the market has to show up in it. */
    @Test
    void equityFollowsThePriceWhileRealisedProfitDoesNot() {
        Long user = player();
        trading.trade(user, symbol, "BUY", BigDecimal.valueOf(1_000), null);

        double before = d(trading.portfolio(user).equity());
        priceIs(120);
        DemoPortfolioResponse after = trading.portfolio(user);

        assertThat(d(after.equity())).isGreaterThan(before);
        assertThat(d(after.unrealisedPnl())).isPositive();
        // Nothing has been sold, so nothing has been realised however far it moved.
        assertThat(d(after.realisedPnl())).isZero();
    }

    @Test
    void sellingRealisesTheMoveAndReturnsTheCash() {
        Long user = player();
        trading.trade(user, symbol, "BUY", BigDecimal.valueOf(1_000), null);
        priceIs(200);
        DemoPortfolioResponse after = trading.trade(user, symbol, "SELL", null, BigDecimal.valueOf(10));

        assertThat(after.positions()).isEmpty();
        assertThat(d(after.realisedPnl())).isPositive();
        assertThat(d(after.cash())).isGreaterThan(d(properties.demo().startingBalance()));
    }

    /** The whole point of play money is that there is a limit on it. */
    @Test
    void cannotSpendMoreCashThanTheAccountHas() {
        Long user = player();
        BigDecimal tooMuch = properties.demo().startingBalance().add(BigDecimal.ONE);

        assertThatThrownBy(() -> trading.trade(user, symbol, "BUY", tooMuch, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(d(trading.portfolio(user).cash())).isEqualTo(d(properties.demo().startingBalance()));
    }

    @Test
    void cannotSellMoreThanIsHeldAndCannotSellWhatWasNeverBought() {
        Long user = player();
        trading.trade(user, symbol, "BUY", BigDecimal.valueOf(100), null);

        // Holds 1 unit at a pinned price of 100.
        assertThatThrownBy(() -> trading.trade(user, symbol, "SELL", null, BigDecimal.valueOf(2)))
                .isInstanceOf(IllegalArgumentException.class);

        Long other = player();
        assertThatThrownBy(() -> trading.trade(other, symbol, "SELL", null, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The fee is what stops a portfolio measuring how often somebody traded. A round trip at an
     * unchanged price has to come back smaller than it went out.
     */
    @Test
    void aRoundTripAtAnUnchangedPriceLosesTheFees() {
        Long user = player();
        trading.trade(user, symbol, "BUY", BigDecimal.valueOf(1_000), null);
        DemoPortfolioResponse after = trading.trade(user, symbol, "SELL", null, BigDecimal.valueOf(10));

        assertThat(d(after.cash())).isLessThan(d(properties.demo().startingBalance()));
        assertThat(d(after.realisedPnl())).isNegative();
    }

    /** A reset rewinds the balance without deleting the run that came before it. */
    @Test
    void resetRestoresTheStartingCashAndCountsItself() {
        Long user = player();
        trading.trade(user, symbol, "BUY", BigDecimal.valueOf(2_000), null);
        assertThat(trading.portfolio(user).positions()).hasSize(1);

        DemoPortfolioResponse after = trading.reset(user);

        assertThat(d(after.cash())).isEqualTo(d(properties.demo().startingBalance()));
        assertThat(after.positions()).isEmpty();
        assertThat(d(after.realisedPnl())).isZero();
        assertThat(after.resets()).isEqualTo(1);
    }

    @Test
    void aTradeWithNoPriceAvailableIsRefusedRatherThanGuessed() {
        Long user = player();
        livePrices.evict();
        when(priceDataProvider.fetchCandles(anyString(), anyString(), any(), any()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> trading.trade(user, symbol, "BUY", BigDecimal.valueOf(100), null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anUnknownPairOrASideThatIsNotBuyOrSellIsRefused() {
        Long user = player();
        assertThatThrownBy(() -> trading.trade(user, "NOPEUSDT", "BUY", BigDecimal.TEN, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> trading.trade(user, symbol, "SHORT", BigDecimal.TEN, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

package com.example.candles.service;

import com.example.candles.CandleFixture;
import com.example.candles.client.CandleData;
import com.example.candles.client.PriceDataProvider;
import com.example.candles.config.CandlesProperties;
import com.example.candles.dto.response.AdminDemoAccount;
import com.example.candles.dto.response.AdminDemoOverview;
import com.example.candles.entity.Asset;
import com.example.candles.entity.Role;
import com.example.candles.entity.User;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.CandleRepository;
import com.example.candles.repository.DemoTradeRepository;
import com.example.candles.repository.UserRepository;
import com.example.candles.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Paper trading seen from the admin side.
 *
 * The feed is pinned for the reason {@code DemoTradingTest} pins it: every assertion here is
 * about money, and a real feed would make "cash is 10,000 minus 500 minus the fee" depend on
 * what BTC cost while the suite ran.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminDemoTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AdminDemoService admin;
    @Autowired private DemoTradingService trading;
    @Autowired private DemoTradeRepository demoTrades;
    @Autowired private UserRepository users;
    @Autowired private AssetRepository assets;
    @Autowired private CandleRepository candles;
    @Autowired private CandlesProperties properties;
    @Autowired private LivePriceService livePrices;
    @Autowired private JwtService jwt;

    @MockitoBean private PriceDataProvider priceDataProvider;

    private String symbol;

    @BeforeEach
    void pinThePrice() {
        Asset asset = assets.findByEnabledTrueOrderByPositionAscSymbolAsc().getFirst();
        symbol = asset.getSymbol();
        CandleFixture.seedIfEmpty(candles, asset, properties.timeframe());
        priceIs(100);
    }

    private void priceIs(double close) {
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

    private User admin(Role role) {
        User user = new User("0x" + UUID.randomUUID().toString().replace("-", ""), "T");
        user.assignRole(role);
        return users.saveAndFlush(user);
    }

    private static double d(BigDecimal v) {
        return v == null ? Double.NaN : v.doubleValue();
    }

    private AdminDemoOverview.Account find(Long userId) {
        // The page is ordered by trade count over every account in the database, so a developer
        // machine can push a freshly seeded one off the first page. Ask for a big enough page
        // rather than assuming a position in it.
        return admin.overview(0, 100).accounts().stream()
                .filter(a -> a.userId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("account " + userId + " not on the page"));
    }

    /**
     * The admin figures come from the same fold the terminal uses, through the same
     * {@link com.example.candles.domain.DemoPortfolio}. Two ways of computing one balance is how
     * an admin page ends up disagreeing with the player about how much money they have — and the
     * player would be right, because theirs is the one the trades produced.
     */
    @Test
    void anAccountReadsTheSameFromBothSides() {
        Long user = player();
        var mine = trading.trade(user, symbol, "BUY", BigDecimal.valueOf(500), null);

        AdminDemoOverview.Account theirs = find(user);

        assertThat(d(theirs.cash())).isEqualTo(d(mine.cash()));
        assertThat(d(theirs.equity())).isEqualTo(d(mine.equity()));
        assertThat(d(theirs.realisedPnl())).isEqualTo(d(mine.realisedPnl()));
        assertThat(theirs.positions()).isEqualTo(mine.positions().size());
        assertThat(theirs.trades()).isEqualTo(1);
        assertThat(theirs.lastTradeAt()).isNotNull();
    }

    /**
     * Opening the terminal creates the account row, so "has a demo account" and "has traded" are
     * different facts. The header shows both, and the gap between them is the question the page
     * exists to answer.
     */
    @Test
    void anAccountThatNeverTradedIsListedAndCountedApart() {
        Long looker = player();
        trading.portfolio(looker);           // opening the terminal is all this does

        AdminDemoOverview overview = admin.overview(0, 100);
        AdminDemoOverview.Account row = find(looker);

        assertThat(row.trades()).isZero();
        assertThat(row.lastTradeAt()).isNull();
        assertThat(d(row.cash())).isEqualTo(d(properties.demo().startingBalance()));
        assertThat(d(row.equity())).isEqualTo(d(properties.demo().startingBalance()));
        assertThat(overview.summary().accounts())
                .isGreaterThanOrEqualTo(overview.summary().tradingAccounts());
        assertThat(overview.summary().startingBalance())
                .isEqualByComparingTo(properties.demo().startingBalance());
        assertThat(overview.summary().feeBps()).isEqualTo(properties.demo().feeBps());
    }

    /**
     * The number nobody else can see. A reset deletes nothing — it moves the mark the fold reads
     * from — so an account can hold rows it no longer shows, which looks like corruption from
     * outside the game and is invisible from inside it.
     */
    @Test
    void aResetLeavesItsTradesOnDiskAndTheAdminViewSaysHowMany() {
        Long user = player();
        trading.trade(user, symbol, "BUY", BigDecimal.valueOf(500), null);
        trading.trade(user, symbol, "BUY", BigDecimal.valueOf(300), null);

        AdminDemoAccount before = admin.account(user);
        assertThat(before.trades()).hasSize(2);
        assertThat(before.tradesBeforeReset()).isZero();

        AdminDemoAccount after = admin.reset(user);

        assertThat(after.resets()).isEqualTo(before.resets() + 1);
        assertThat(after.trades()).isEmpty();
        assertThat(after.positions()).isEmpty();
        assertThat(d(after.cash())).isEqualTo(d(properties.demo().startingBalance()));
        // Nothing was deleted: the two rows are still there, they are simply behind the mark.
        assertThat(after.tradesBeforeReset()).isEqualTo(2);
        assertThat(demoTrades.count()).isGreaterThanOrEqualTo(2);
    }

    /**
     * A round trip at an unchanged price loses exactly the two fees, and the admin view has to
     * report that as realised rather than as a balance that drifted. Same property
     * {@code DemoTradingTest} pins from the player's side, asserted here because these are
     * different code paths reading the same rows.
     */
    @Test
    void realisedLossFromFeesShowsUpOnTheAdminRow() {
        Long user = player();
        trading.trade(user, symbol, "BUY", BigDecimal.valueOf(500), null);
        BigDecimal held = trading.portfolio(user).positions().getFirst().quantity();
        trading.trade(user, symbol, "SELL", null, held);

        AdminDemoOverview.Account row = find(user);

        assertThat(d(row.realisedPnl())).isLessThan(0);
        assertThat(row.positions()).isZero();
        assertThat(d(row.equity())).isEqualTo(d(row.cash()));
        assertThat(d(row.cash())).isLessThan(d(properties.demo().startingBalance()));
    }

    /**
     * An account that never opened the terminal has no row to read, and saying so is better than
     * inventing an empty portfolio for a user who has none.
     */
    @Test
    void anAccountWithNoTerminalIsAnErrorRatherThanAnEmptyOne() {
        assertThatThrownBy(() -> admin.account(player()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chưa mở sàn demo");
    }

    @Test
    void everyRouteIsClosedToEveryoneButAdmins() throws Exception {
        Long user = player();
        trading.portfolio(user);

        mockMvc.perform(get("/api/admin/demo")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/demo")
                        .header("Authorization", "Bearer " + jwt.createAccessToken(admin(Role.USER))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/demo")
                        .header("Authorization", "Bearer " + jwt.createAccessToken(admin(Role.ADMIN))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/demo/" + user + "/reset")
                        .header("Authorization", "Bearer " + jwt.createAccessToken(admin(Role.USER))))
                .andExpect(status().isForbidden());
    }
}

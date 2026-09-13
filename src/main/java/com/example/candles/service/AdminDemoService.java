package com.example.candles.service;

import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.DemoPortfolio;
import com.example.candles.dto.response.AdminDemoAccount;
import com.example.candles.dto.response.AdminDemoOverview;
import com.example.candles.entity.Asset;
import com.example.candles.entity.DemoAccount;
import com.example.candles.entity.TradeSide;
import com.example.candles.entity.User;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.DemoAccountRepository;
import com.example.candles.repository.DemoTradeRepository;
import com.example.candles.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The admin's view of paper trading.
 *
 * Everything here is folded out of the trade log the same way {@link DemoTradingService} folds
 * it for the player, through the same {@link DemoPortfolio}. That is the point: an admin page
 * that computed a balance its own way would eventually disagree with the terminal about how
 * much money somebody has, and the player would be right.
 *
 * One query supplies the fills for a whole page of accounts rather than one fold per row. The
 * mark a reset moved is different for every account, so it comes from the join to
 * {@code demo_accounts} instead of from a parameter — which is what keeps a reset meaning the
 * same thing here as it does in the player's own terminal.
 */
@Service
public class AdminDemoService {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 100;
    /** Enough of an account's history to see how it was traded, not so much it needs a pager. */
    private static final int RECENT_TRADES = 40;

    private final DemoAccountRepository accounts;
    private final DemoTradeRepository trades;
    private final UserRepository users;
    private final AssetRepository assets;
    private final LivePriceService prices;
    private final DemoTradingService demoTrading;
    private final CandlesProperties properties;
    private final Clock clock;

    public AdminDemoService(DemoAccountRepository accounts,
                            DemoTradeRepository trades,
                            UserRepository users,
                            AssetRepository assets,
                            LivePriceService prices,
                            DemoTradingService demoTrading,
                            CandlesProperties properties,
                            Clock clock) {
        this.accounts = accounts;
        this.trades = trades;
        this.users = users;
        this.assets = assets;
        this.prices = prices;
        this.demoTrading = demoTrading;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AdminDemoOverview overview(Integer page, Integer size) {
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : Math.clamp(size, 1, MAX_PAGE_SIZE);
        int pageIndex = page == null ? 0 : Math.max(page, 0);

        List<Object[]> rows = accounts.accountPage(pageSize, pageIndex * pageSize);
        List<Long> userIds = rows.stream().map(row -> asLong(row[0])).toList();
        Map<Long, DemoPortfolio> portfolios = fold(userIds);
        Map<String, BigDecimal> priceBySymbol = pricesFor(portfolios.values());

        List<AdminDemoOverview.Account> page1 = rows.stream()
                .map(row -> account(row, portfolios, priceBySymbol))
                .toList();

        long total = accounts.count();
        return new AdminDemoOverview(summary(total), page1, pageIndex, pageSize, total,
                (long) pageIndex * pageSize + page1.size() < total);
    }

    @Transactional(readOnly = true)
    public AdminDemoAccount account(Long userId) {
        DemoAccount account = accounts.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Tài khoản #" + userId + " chưa mở sàn demo."));
        User user = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản #" + userId));

        DemoPortfolio portfolio = fold(List.of(userId))
                .getOrDefault(userId, DemoPortfolio.of(startingBalance(), List.of()));
        Map<String, BigDecimal> priceBySymbol = pricesFor(List.of(portfolio));

        List<AdminDemoAccount.Position> positions = new ArrayList<>();
        BigDecimal unrealised = BigDecimal.ZERO;
        boolean everythingPriced = true;

        for (Map.Entry<String, DemoPortfolio.Holding> entry : portfolio.holdings().entrySet()) {
            DemoPortfolio.Holding holding = entry.getValue();
            if (holding.quantity().signum() <= 0) {
                continue;
            }
            BigDecimal price = priceBySymbol.get(entry.getKey());
            BigDecimal value = price == null ? null : holding.quantity().multiply(price, MC);
            BigDecimal open = value == null ? null : value.subtract(holding.costBasis());
            if (open == null) {
                everythingPriced = false;
            } else {
                unrealised = unrealised.add(open);
            }
            positions.add(new AdminDemoAccount.Position(entry.getKey(), holding.quantity(),
                    holding.averageCost(), price, value, open));
        }

        BigDecimal openPnl = everythingPriced ? unrealised : null;
        BigDecimal equity = openPnl == null ? null
                : portfolio.cash().add(heldValue(positions));

        List<AdminDemoAccount.Fill> fills = trades
                .findRecentSince(userId, account.getOpenedAt(), PageRequest.of(0, RECENT_TRADES))
                .stream()
                .map(t -> new AdminDemoAccount.Fill(t.getAsset().getSymbol(), t.getSide().name(),
                        t.getQuantity(), t.getPrice(), t.getFee(), t.getCreatedAt()))
                .toList();

        return new AdminDemoAccount(userId, user.getWalletAddress(), user.getDisplayName(),
                account.getOpenedAt(), account.getResets(), trades.countBeforeReset(userId),
                startingBalance(), properties.demo().feeBps(),
                portfolio.cash(), portfolio.realisedPnl(), openPnl, equity, positions, fills);
    }

    /**
     * Rewinds one account, through the player's own reset rather than a copy of it.
     *
     * The same call the player's own button makes, which is what makes it safe: it deletes
     * nothing, it moves {@code opened_at} and bumps the reset counter, and the trades before
     * the mark stay on disk. An admin resetting somebody is undoing a position, not erasing a
     * history, and this is the only write on this page for that reason.
     */
    @Transactional
    public AdminDemoAccount reset(Long userId) {
        demoTrading.reset(userId);
        return account(userId);
    }

    private AdminDemoOverview.Summary summary(long accountCount) {
        Instant now = clock.instant();
        Object[] row = unwrap(trades.tradeSummary(
                now.truncatedTo(ChronoUnit.DAYS), now.minus(7, ChronoUnit.DAYS)));
        return new AdminDemoOverview.Summary(accountCount, asLong(row[4]), asLong(row[0]),
                asLong(row[1]), asLong(row[2]), (BigDecimal) row[3], accounts.totalResets(),
                startingBalance(), properties.demo().feeBps());
    }

    private AdminDemoOverview.Account account(Object[] row, Map<Long, DemoPortfolio> portfolios,
                                              Map<String, BigDecimal> priceBySymbol) {
        Long userId = asLong(row[0]);
        DemoPortfolio portfolio = portfolios.getOrDefault(userId,
                DemoPortfolio.of(startingBalance(), List.of()));

        int positions = 0;
        BigDecimal held = BigDecimal.ZERO;
        BigDecimal basis = BigDecimal.ZERO;
        boolean everythingPriced = true;

        for (Map.Entry<String, DemoPortfolio.Holding> entry : portfolio.holdings().entrySet()) {
            DemoPortfolio.Holding holding = entry.getValue();
            if (holding.quantity().signum() <= 0) {
                continue;
            }
            positions++;
            BigDecimal price = priceBySymbol.get(entry.getKey());
            if (price == null) {
                everythingPriced = false;
                continue;
            }
            held = held.add(holding.quantity().multiply(price, MC));
            basis = basis.add(holding.costBasis());
        }

        /* Cash and realised P&L need no prices and are always exact. The other three need every
           held position priced, and go null together when one is not: a whole-account figure
           that silently dropped a holding would be worse than a gap, and this is a list
           somebody is comparing accounts across. */
        BigDecimal holdingsValue = everythingPriced ? held : null;
        BigDecimal unrealised = everythingPriced ? held.subtract(basis) : null;
        BigDecimal equity = everythingPriced ? portfolio.cash().add(held) : null;

        return new AdminDemoOverview.Account(userId, String.valueOf(row[1]), String.valueOf(row[2]),
                instant(row[3]), asInt(row[4]), asLong(row[5]), instant(row[6]), positions,
                portfolio.cash(), portfolio.realisedPnl(), holdingsValue, unrealised, equity);
    }

    /** One query for the whole page, folded per account through the players' own accounting. */
    private Map<Long, DemoPortfolio> fold(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<DemoPortfolio.Fill>> byUser = new LinkedHashMap<>();
        for (Object[] row : trades.fillsForAccounts(userIds)) {
            byUser.computeIfAbsent(asLong(row[0]), id -> new ArrayList<>())
                    .add(new DemoPortfolio.Fill(String.valueOf(row[1]),
                            TradeSide.BUY.name().equals(String.valueOf(row[2])),
                            (BigDecimal) row[3], (BigDecimal) row[4], (BigDecimal) row[5]));
        }

        Map<Long, DemoPortfolio> folded = new LinkedHashMap<>();
        byUser.forEach((id, fills) -> folded.put(id, DemoPortfolio.of(startingBalance(), fills)));
        return folded;
    }

    /**
     * Live prices for every symbol actually held, once each.
     *
     * Looked up by symbol against every asset rather than only the enabled ones: a pair switched
     * off does not empty the positions anybody was holding in it, and an admin looking at those
     * positions is often looking precisely because it was switched off.
     */
    private Map<String, BigDecimal> pricesFor(Iterable<DemoPortfolio> portfolios) {
        Map<String, Asset> bySymbol = new HashMap<>();
        for (Asset asset : assets.findAll()) {
            bySymbol.put(asset.getSymbol(), asset);
        }

        Map<String, BigDecimal> priced = new HashMap<>();
        for (DemoPortfolio portfolio : portfolios) {
            portfolio.holdings().forEach((symbol, holding) -> {
                if (holding.quantity().signum() <= 0 || priced.containsKey(symbol)) {
                    return;
                }
                Asset asset = bySymbol.get(symbol);
                if (asset == null) {
                    return;
                }
                BigDecimal price = prices.price(asset);
                if (price != null) {
                    priced.put(symbol, price);
                }
            });
        }
        return priced;
    }

    private static BigDecimal heldValue(List<AdminDemoAccount.Position> positions) {
        return positions.stream()
                .map(AdminDemoAccount.Position::value)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal startingBalance() {
        return properties.demo().startingBalance();
    }

    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof Instant already) return already;
        if (value instanceof java.time.OffsetDateTime offset) return offset.toInstant();
        if (value instanceof java.time.LocalDateTime local) return local.toInstant(ZoneOffset.UTC);
        throw new IllegalStateException("Unexpected timestamp type: " + value.getClass());
    }

    private static Object[] unwrap(Object[] row) {
        if (row.length == 1 && row[0] instanceof Object[] inner) {
            return inner;
        }
        return row;
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}

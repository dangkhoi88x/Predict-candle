package com.example.candles.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.DemoPortfolio;
import com.example.candles.dto.response.DemoPortfolioResponse;
import com.example.candles.entity.Asset;
import com.example.candles.entity.DemoAccount;
import com.example.candles.entity.DemoTrade;
import com.example.candles.entity.TradeSide;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.DemoAccountRepository;
import com.example.candles.repository.DemoTradeRepository;

/**
 * Paper trading on live prices: play money, real quotes, no leverage.
 *
 * Nothing about the account is stored except its trades. Cash, holdings and cost basis are
 * folded by {@link DemoPortfolio} on every read — see that class for why a balance column would
 * be a liability rather than a shortcut.
 *
 * <h2>What the client is not trusted with</h2>
 *
 * The price. Every fill is priced from {@link LivePriceService} inside the same transaction that
 * records it, so a browser can name the asset and the size but never the number it filled at.
 * That is the same rule the round token enforces for guesses, and for the same reason: any
 * figure the client supplies is a figure the client can choose.
 *
 * <h2>Why the account row is locked</h2>
 *
 * Because the balance is derived there is no balance column to update atomically, so nothing
 * would stop two simultaneous buys from both reading the same cash, both finding it enough, and
 * both inserting. The lock serialises one player's trades against each other and touches nobody
 * else's.
 */
@Service
public class DemoTradingService {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal BPS = BigDecimal.valueOf(10_000);

    /** Below this a trade is dust: it moves nothing and exists only to spam the log. */
    private static final BigDecimal MIN_TRADE_USD = BigDecimal.ONE;

    private final AssetRepository assets;
    private final DemoAccountRepository accounts;
    private final DemoTradeRepository trades;
    private final LivePriceService prices;
    private final CandlesProperties properties;
    private final Clock clock;

    public DemoTradingService(AssetRepository assets,
                              DemoAccountRepository accounts,
                              DemoTradeRepository trades,
                              LivePriceService prices,
                              CandlesProperties properties,
                              Clock clock) {
        this.assets = assets;
        this.accounts = accounts;
        this.trades = trades;
        this.prices = prices;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public DemoPortfolioResponse portfolio(Long userId) {
        return view(account(userId));
    }

    /** Rewinds to a fresh balance. Nothing is deleted; the fold stops reading before the mark. */
    @Transactional
    public DemoPortfolioResponse reset(Long userId) {
        DemoAccount account = accounts.findForUpdate(userId).orElseGet(() -> account(userId));
        account.reset(clock.instant());
        return view(accounts.save(account));
    }

    @Transactional
    public DemoPortfolioResponse trade(Long userId, String assetSymbol, String sideName,
                                       BigDecimal amountUsd, BigDecimal quantity) {
        TradeSide side = parseSide(sideName);
        Asset asset = tradable(assetSymbol);

        // Opened first so there is always a row to lock; the lock is what serialises this
        // player's own trades against each other.
        account(userId);
        DemoAccount account = accounts.findForUpdate(userId).orElseThrow();

        BigDecimal price = prices.price(asset);
        if (price == null || price.signum() <= 0) {
            throw new IllegalStateException("Chưa có giá cho " + asset.getSymbol() + ". Thử lại sau ít giây.");
        }

        DemoPortfolio portfolio = fold(account);
        DemoTrade executed = side == TradeSide.BUY
                ? buy(userId, asset, price, amountUsd, portfolio)
                : sell(userId, asset, price, quantity, portfolio);

        trades.save(executed);
        return view(account);
    }

    private DemoTrade buy(Long userId, Asset asset, BigDecimal price,
                          BigDecimal amountUsd, DemoPortfolio portfolio) {
        if (amountUsd == null || amountUsd.compareTo(MIN_TRADE_USD) < 0) {
            throw new IllegalArgumentException("Số tiền mua tối thiểu là $" + MIN_TRADE_USD + ".");
        }
        BigDecimal fee = amountUsd.multiply(BigDecimal.valueOf(properties.demo().feeBps()), MC)
                .divide(BPS, MC);
        BigDecimal total = amountUsd.add(fee);
        if (total.compareTo(portfolio.cash()) > 0) {
            throw new IllegalArgumentException("Không đủ tiền: cần $"
                    + total.setScale(2, java.math.RoundingMode.HALF_UP) + ", còn $"
                    + portfolio.cash().setScale(2, java.math.RoundingMode.HALF_UP) + ".");
        }
        return new DemoTrade(userId, asset, TradeSide.BUY,
                amountUsd.divide(price, MC), price, fee, clock.instant());
    }

    private DemoTrade sell(Long userId, Asset asset, BigDecimal price,
                           BigDecimal quantity, DemoPortfolio portfolio) {
        BigDecimal held = portfolio.holding(asset.getSymbol()).quantity();
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("Số lượng bán phải lớn hơn 0.");
        }
        if (quantity.compareTo(held) > 0) {
            throw new IllegalArgumentException("Bán quá số đang giữ: có " + held.stripTrailingZeros().toPlainString() + ".");
        }
        BigDecimal notional = quantity.multiply(price, MC);
        BigDecimal fee = notional.multiply(BigDecimal.valueOf(properties.demo().feeBps()), MC).divide(BPS, MC);
        return new DemoTrade(userId, asset, TradeSide.SELL, quantity, price, fee, clock.instant());
    }

    private DemoAccount account(Long userId) {
        return accounts.findById(userId)
                .orElseGet(() -> accounts.save(new DemoAccount(userId, clock.instant())));
    }

    private DemoPortfolio fold(DemoAccount account) {
        List<DemoPortfolio.Fill> fills = trades.findSince(account.getUserId(), account.getOpenedAt())
                .stream()
                .map(t -> new DemoPortfolio.Fill(t.getAsset().getSymbol(), t.getSide() == TradeSide.BUY,
                        t.getQuantity(), t.getPrice(), t.getFee()))
                .toList();
        return DemoPortfolio.of(properties.demo().startingBalance(), fills);
    }

    private Asset tradable(String symbol) {
        Asset asset = assets.findBySymbol(symbol.toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Không có cặp: " + symbol));
        if (!asset.isEnabled()) {
            throw new IllegalArgumentException("Cặp này đang tạm tắt: " + asset.getSymbol());
        }
        return asset;
    }

    private static TradeSide parseSide(String side) {
        try {
            return TradeSide.valueOf(side.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Lệnh phải là BUY hoặc SELL.");
        }
    }

    private DemoPortfolioResponse view(DemoAccount account) {
        DemoPortfolio portfolio = fold(account);
        List<Asset> tradable = assets.findByEnabledTrueOrderByPositionAscSymbolAsc();

        List<DemoPortfolioResponse.Market> markets = tradable.stream()
                .map(a -> new DemoPortfolioResponse.Market(a.getSymbol(), a.getName(), prices.price(a)))
                .toList();

        List<DemoPortfolioResponse.Position> positions = new ArrayList<>();
        BigDecimal held = BigDecimal.ZERO;
        BigDecimal unrealised = BigDecimal.ZERO;

        for (Asset asset : tradable) {
            DemoPortfolio.Holding holding = portfolio.holding(asset.getSymbol());
            if (holding.quantity().signum() <= 0) {
                continue;
            }
            BigDecimal price = prices.price(asset);
            // An unknown price leaves the position's worth unknown rather than zero, and keeps
            // it out of equity — a number that quietly drops a holding is worse than a gap.
            BigDecimal value = price == null ? null : holding.quantity().multiply(price, MC);
            BigDecimal open = value == null ? null : value.subtract(holding.costBasis());
            if (value != null) {
                held = held.add(value);
                unrealised = unrealised.add(open);
            }
            positions.add(new DemoPortfolioResponse.Position(
                    asset.getSymbol(), asset.getName(), holding.quantity(),
                    holding.averageCost(), price, value, open));
        }

        List<DemoPortfolioResponse.Fill> recent = trades.findSince(account.getUserId(), account.getOpenedAt())
                .reversed().stream()
                .limit(25)
                .map(t -> new DemoPortfolioResponse.Fill(t.getAsset().getSymbol(), t.getSide().name(),
                        t.getQuantity(), t.getPrice(), t.getFee(), t.getCreatedAt()))
                .toList();

        return new DemoPortfolioResponse(
                portfolio.cash(),
                portfolio.cash().add(held),
                properties.demo().startingBalance(),
                portfolio.realisedPnl(),
                unrealised,
                properties.demo().feeBps(),
                account.getResets(),
                account.getOpenedAt(),
                List.copyOf(positions),
                markets,
                recent);
    }
}

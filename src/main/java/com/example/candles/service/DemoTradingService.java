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
import com.example.candles.client.Timeframes;
import com.example.candles.domain.CandleAggregator;
import com.example.candles.domain.DemoPortfolio;
import com.example.candles.dto.response.DatedCandleDto;
import com.example.candles.dto.response.DemoChartResponse;
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

    /** What the chart offers. Anything longer than the stored timeframe is folded from it. */
    private static final java.util.Set<String> CHART_TIMEFRAMES = java.util.Set.of("1h", "4h", "1d");

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal BPS = BigDecimal.valueOf(10_000);

    /** Below this a trade is dust: it moves nothing and exists only to spam the log. */
    private static final BigDecimal MIN_TRADE_USD = BigDecimal.ONE;

    private final AssetRepository assets;
    private final com.example.candles.repository.CandleRepository candles;
    private final DemoAccountRepository accounts;
    private final DemoTradeRepository trades;
    private final LivePriceService prices;
    private final MarketStatsService marketStats;
    private final CandlesProperties properties;
    private final Clock clock;

    public DemoTradingService(AssetRepository assets,
                              com.example.candles.repository.CandleRepository candles,
                              DemoAccountRepository accounts,
                              DemoTradeRepository trades,
                              LivePriceService prices,
                              MarketStatsService marketStats,
                              CandlesProperties properties,
                              Clock clock) {
        this.assets = assets;
        this.candles = candles;
        this.accounts = accounts;
        this.trades = trades;
        this.prices = prices;
        this.marketStats = marketStats;
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

    /**
     * Recent settled candles for one market, at one of the timeframes the chart offers.
     *
     * Only the configured timeframe is stored, so anything longer is folded out of it by
     * {@link CandleAggregator} rather than synced and kept separately. That means reading
     * {@code limit * factor} stored candles and rolling them up — cheap at these sizes, and it
     * leaves nothing new to keep in step with the hourly sync.
     *
     * Read from stored history rather than the feed: the chart is a picture of what has
     * happened, and the candle still forming is already on screen as the live price above it.
     */
    @Transactional(readOnly = true)
    public DemoChartResponse chart(String assetSymbol, String timeframe, int limit) {
        Asset asset = tradable(assetSymbol);
        String stored = properties.timeframe();
        String target = CHART_TIMEFRAMES.contains(timeframe) ? timeframe : stored;
        int span = Math.clamp(limit, 20, 400);

        // How many stored candles one target bar is worth. A target shorter than what is stored
        // cannot be built at all, so it falls back to the stored timeframe rather than inventing
        // detail that was never recorded.
        long factor = Math.max(1,
                Timeframes.parse(target).toMillis() / Timeframes.parse(stored).toMillis());
        int needed = (int) Math.min(span * factor + factor, 20_000);

        long total = candles.countByAssetAndTimeframe(asset, stored);
        int from = (int) Math.max(0, total - needed);
        List<CandleAggregator.Bar> source = candles.findWindow(asset.getId(), stored, from, needed)
                .stream()
                .map(c -> new CandleAggregator.Bar(c.getOpenTime(), c.getOpen(), c.getHigh(),
                        c.getLow(), c.getClose(), c.getVolume()))
                .toList();

        List<CandleAggregator.Bar> rolled = CandleAggregator.rollUp(source, target);
        // The oldest bar of the window is usually a partial period — the window started
        // mid-bucket — so it is dropped rather than drawn as a short candle that never existed.
        if (rolled.size() > span) {
            rolled = rolled.subList(rolled.size() - span, rolled.size());
        }

        return new DemoChartResponse(asset.getSymbol(), target,
                rolled.stream()
                        .map(b -> new DatedCandleDto(b.time(), b.open(), b.high(), b.low(), b.close()))
                        .toList());
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
                .map(a -> {
                    MarketStatsService.Stats stats = marketStats.stats(a);
                    return new DemoPortfolioResponse.Market(a.getSymbol(), a.getName(),
                            prices.price(a), stats.changePct(), stats.volume());
                })
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

package com.example.candles.service;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.example.candles.client.Timeframes;
import com.example.candles.config.CandlesProperties;
import com.example.candles.dto.OpsSnapshot;
import com.example.candles.entity.Asset;
import com.example.candles.entity.Candle;
import com.example.candles.entity.Role;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.BlogPostRepository;
import com.example.candles.repository.CandleRepository;
import com.example.candles.repository.ContentItemRepository;
import com.example.candles.repository.GuessResultRepository;
import com.example.candles.repository.UserRepository;

/**
 * Gathers the operations snapshot and runs a manual candle sync.
 *
 * Deliberately read-only apart from the sync: this panel exists to answer questions, and an
 * admin screen that can quietly change how the game behaves is a second, undocumented source
 * of truth beside application.yaml.
 */
@Service
public class OpsService {

    private final AssetRepository assetRepository;
    private final CandleRepository candleRepository;
    private final GuessResultRepository guessResultRepository;
    private final UserRepository userRepository;
    private final BlogPostRepository blogPostRepository;
    private final ContentItemRepository contentItemRepository;
    private final CandleSyncService candleSyncService;
    private final CandlesProperties properties;
    private final ObjectProvider<Flyway> flyway;

    public OpsService(AssetRepository assetRepository,
                      CandleRepository candleRepository,
                      GuessResultRepository guessResultRepository,
                      UserRepository userRepository,
                      BlogPostRepository blogPostRepository,
                      ContentItemRepository contentItemRepository,
                      CandleSyncService candleSyncService,
                      CandlesProperties properties,
                      ObjectProvider<Flyway> flyway) {
        this.assetRepository = assetRepository;
        this.candleRepository = candleRepository;
        this.guessResultRepository = guessResultRepository;
        this.userRepository = userRepository;
        this.blogPostRepository = blogPostRepository;
        this.contentItemRepository = contentItemRepository;
        this.candleSyncService = candleSyncService;
        this.properties = properties;
        this.flyway = flyway;
    }

    @Transactional(readOnly = true)
    public OpsSnapshot snapshot() {
        Instant now = Instant.now();
        return new OpsSnapshot(assetHealth(now), schema(), settings(), activity(now), now);
    }

    /** Runs the same delta fetch the hourly job runs, for one asset, now. */
    @Transactional
    public OpsSnapshot.AssetHealth syncNow(String symbol) {
        Asset asset = assetRepository.findBySymbol(symbol.toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Không có asset: " + symbol));
        candleSyncService.sync(asset);
        return health(asset, Instant.now());
    }

    private List<OpsSnapshot.AssetHealth> assetHealth(Instant now) {
        return assetRepository.findAll().stream()
                .map(asset -> health(asset, now))
                .sorted((a, b) -> a.symbol().compareTo(b.symbol()))
                .toList();
    }

    private OpsSnapshot.AssetHealth health(Asset asset, Instant now) {
        String timeframe = properties.timeframe();
        long count = candleRepository.countByAssetAndTimeframe(asset, timeframe);
        Instant latest = candleRepository
                .findTopByAssetAndTimeframeOrderByOpenTimeDesc(asset, timeframe)
                .map(Candle::getOpenTime)
                .orElse(null);
        Instant first = count > 0
                ? candleRepository.findWindow(asset.getId(), timeframe, 0, 1).stream()
                        .findFirst().map(Candle::getOpenTime).orElse(null)
                : null;

        Long lag = latest == null ? null : Duration.between(latest, now).toMinutes();
        /*
         * One period of slack on top of the timeframe: the newest candle is the one that last
         * closed, so a healthy 1h feed is always somewhere between 0 and 60 minutes behind,
         * and the hourly job runs at five past.
         */
        long tolerance = com.example.candles.client.Timeframes.parse(timeframe).toMinutes() * 2 + 10;
        return new OpsSnapshot.AssetHealth(asset.getSymbol(), asset.getName(), timeframe, count,
                first, latest, lag, lag == null || lag > tolerance);
    }

    private OpsSnapshot.Schema schema() {
        Flyway instance = flyway.getIfAvailable();
        if (instance == null) {
            return new OpsSnapshot.Schema("—", 0, 0, "validate");
        }
        MigrationInfoService info = instance.info();
        MigrationInfo current = info.current();
        /*
         * Flyway's own applied/pending split, not a count of rows with an install date. A
         * baselined database carries the migrations below its baseline with no install date
         * at all, and counting those as pending reports a healthy schema as behind.
         */
        return new OpsSnapshot.Schema(
                current == null || current.getVersion() == null ? "—" : current.getVersion().toString(),
                info.applied().length,
                info.pending().length,
                "validate");
    }

    private OpsSnapshot.GameSettings settings() {
        CandlesProperties.Round round = properties.round();
        return new OpsSnapshot.GameSettings(properties.timeframe(), round.visibleCandles(),
                round.guessesPerChart(), round.revealCandlesAfterComplete(), round.contextPadding(),
                round.timing().seconds(), round.rateLimit().roundsPerMinute(),
                round.rateLimit().guessesPerMinute());
    }

    private OpsSnapshot.Activity activity(Instant now) {
        Object[] today = unwrap(guessResultRepository.activitySince(now.truncatedTo(ChronoUnit.DAYS)));
        Object[] week = unwrap(guessResultRepository.activitySince(now.minus(7, ChronoUnit.DAYS)));

        return new OpsSnapshot.Activity(
                userRepository.count(),
                userRepository.findByRole(Role.ADMIN).size(),
                asLong(today[0]), asLong(today[1]), asLong(week[0]),
                blogPostRepository.count(),
                blogPostRepository.findByPublishedTrueOrderByPositionAscIdAsc().size(),
                contentItemRepository.count());
    }

    /**
     * A single-row aggregate query comes back as Object[] of the columns, but some providers
     * wrap it in another Object[] of rows. Unwrapping here keeps that detail out of the caller.
     */
    private static Object[] unwrap(Object[] row) {
        if (row.length == 1 && row[0] instanceof Object[] inner) {
            return inner;
        }
        return row;
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}

package com.example.candles.service;

import com.example.candles.client.CandleData;
import com.example.candles.client.PriceDataProvider;
import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.LiveRound;
import com.example.candles.dto.response.LiveRoundHistoryResponse;
import com.example.candles.dto.response.LiveRoundResponse;
import com.example.candles.entity.Asset;
import com.example.candles.entity.Candle;
import com.example.candles.entity.Direction;
import com.example.candles.entity.LivePrediction;
import com.example.candles.entity.User;
import com.example.candles.repository.CandleRepository;
import com.example.candles.repository.LivePredictionRepository;
import com.example.candles.config.ClockConfig;
import com.example.candles.repository.UserRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The live round: one shared call on the candle currently forming on the real exchange.
 *
 * There is no round table and no settlement job. {@link LiveRound#at} names the round from the
 * clock alone, so "which round is this" never needs asking anyone — and a round's outcome is
 * read the same way, by comparing a call against the candle Binance eventually confirms. The
 * only state this owns is {@link LivePrediction}: who called what, once, per round.
 */
@Service
public class LiveRoundService {

    private final RoundSelectionService roundSelectionService;
    private final CandleRepository candleRepository;
    private final LivePredictionRepository livePredictionRepository;
    private final UserRepository userRepository;
    private final PriceDataProvider priceDataProvider;
    private final CandlesProperties properties;
    private final Clock clock;
    private final Cache<String, CandleData> livePriceCache;

    /**
     * Reads the clock through {@link Clock} rather than {@code Instant.now()} so a test can pin
     * it to an instant known to be inside a round's open window — see {@link ClockConfig} for
     * why that distinction turned out to matter in production, not just in theory.
     */
    public LiveRoundService(RoundSelectionService roundSelectionService,
                            CandleRepository candleRepository,
                            LivePredictionRepository livePredictionRepository,
                            UserRepository userRepository,
                            PriceDataProvider priceDataProvider,
                            CandlesProperties properties,
                            Clock clock) {
        this.roundSelectionService = roundSelectionService;
        this.candleRepository = candleRepository;
        this.livePredictionRepository = livePredictionRepository;
        this.userRepository = userRepository;
        this.priceDataProvider = priceDataProvider;
        this.properties = properties;
        this.clock = clock;
        this.livePriceCache = Caffeine.newBuilder()
                .expireAfterWrite(properties.live().priceCacheTtl())
                .build();
    }

    public LiveRoundResponse snapshot(String assetSymbol, Long callerId) {
        Asset asset = roundSelectionService.resolveAsset(assetSymbol);
        String timeframe = properties.timeframe();
        Instant now = clock.instant();
        LiveRound round = LiveRound.at(now, timeframe, properties.live().lockBefore());

        String myDirection = callerId == null ? null
                : livePredictionRepository.findByUserAndAssetAndTimeframeAndOpenTime(
                        userRepository.getReferenceById(callerId), asset, timeframe, round.openTime())
                        .map(p -> p.getDirection().name())
                        .orElse(null);

        return buildResponse(asset, timeframe, round, now, myDirection);
    }

    /** The roster shown alongside the pool — plenty to scroll through, not the whole history. */
    private static final int MAX_PARTICIPANTS_SHOWN = 50;

    /**
     * Shared by {@link #snapshot} and {@link #predict} so a placed call doesn't have to re-fetch
     * the asset, re-derive the round, or re-query the crowd totals it already computed a few
     * lines earlier just to describe them back to the caller.
     */
    private LiveRoundResponse buildResponse(Asset asset, String timeframe, LiveRound round, Instant now,
                                            String myDirection) {
        CandleData forming = liveCandle(asset, timeframe, round, now);
        BigDecimal openPrice = forming != null ? forming.open() : null;
        BigDecimal livePrice = forming != null ? forming.close() : null;

        int[] sides = sideCounts(asset.getId(), timeframe, round.openTime());
        List<LiveRoundResponse.Participant> participants =
                livePredictionRepository.findParticipants(asset.getId(), timeframe, round.openTime())
                        .stream()
                        .limit(MAX_PARTICIPANTS_SHOWN)
                        .map(p -> new LiveRoundResponse.Participant(
                                p.getUser().getDisplayName(), p.getUser().getShortWalletAddress(),
                                p.getDirection().name(), p.getCreatedAt()))
                        .toList();

        return new LiveRoundResponse(asset.getSymbol(), timeframe, round.number(), round.openTime(),
                round.lockAt(), round.closeAt(), round.isLocked(now), openPrice, livePrice,
                sides[0], sides[1], myDirection, participants);
    }

    /**
     * The candle covering {@code round}, however far it has gotten. Reuses
     * {@link PriceDataProvider#fetchCandles} — the same call {@code CandleSyncService} makes for
     * settled history — but where that service stops one millisecond short of "now" specifically
     * to avoid ever storing a candle that is still moving, this asks for exactly that candle,
     * because a still-moving price is the entire point of a live round.
     *
     * {@code round} is always the round covering {@code now} — every caller builds it via
     * {@code LiveRound.at(now, ...)} immediately before this call — so it can never have closed
     * relative to that same {@code now}. There is deliberately no "read the settled row instead"
     * branch here: that shortcut only makes sense for a round whose identity is already known to
     * be in the past, which is exactly what {@link #history} does by reading
     * {@link com.example.candles.repository.CandleRepository} directly rather than going through
     * this method at all.
     */
    private CandleData liveCandle(Asset asset, String timeframe, LiveRound round, Instant now) {
        String cacheKey = asset.getId() + ":" + timeframe;
        CandleData cached = livePriceCache.getIfPresent(cacheKey);
        if (cached != null && !cached.openTime().isBefore(round.openTime())) {
            return cached;
        }
        List<CandleData> page = priceDataProvider.fetchCandles(
                asset.getSymbol(), timeframe, round.openTime(), now.plusMillis(1));
        if (page.isEmpty()) {
            return null;
        }
        CandleData latest = page.getLast();
        livePriceCache.put(cacheKey, latest);
        return latest;
    }

    /**
     * {@code callerId} is trusted to be non-null: {@code /api/live/predict} is behind
     * {@code .authenticated()} in SecurityConfig, the same way {@code /api/stats/**} is, so an
     * anonymous caller never reaches here at all.
     */
    public LiveRoundResponse predict(String assetSymbol, Direction direction, Long callerId) {
        Asset asset = roundSelectionService.resolveAsset(assetSymbol);
        String timeframe = properties.timeframe();
        Instant now = clock.instant();
        LiveRound round = LiveRound.at(now, timeframe, properties.live().lockBefore());

        if (round.isLocked(now)) {
            throw new IllegalStateException("Vòng này đã khoá dự đoán, chờ vòng tiếp theo.");
        }

        User user = userRepository.getReferenceById(callerId);
        /*
         * Checked before the insert rather than relying only on the catch below: an insert that
         * fails its unique constraint leaves the persistence context needing a rollback, so a
         * caller reusing the same transaction — every HTTP request in this test class does,
         * MockMvc included — would find every later query broken by an exception this one
         * request already recovered from. The catch stays as the real safety net for two
         * concurrent requests racing past this check; it just should not be how the ordinary,
         * single-request replay is caught.
         */
        if (livePredictionRepository.findByUserAndAssetAndTimeframeAndOpenTime(
                user, asset, timeframe, round.openTime()).isPresent()) {
            throw new IllegalStateException("Bạn đã dự đoán vòng này rồi.");
        }
        try {
            livePredictionRepository.save(new LivePrediction(user, asset, timeframe, round.openTime(), direction));
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("Bạn đã dự đoán vòng này rồi.");
        }
        // The direction just recorded — no need to read it back from a query that only just committed.
        return buildResponse(asset, timeframe, round, now, direction.name());
    }

    /** [longCount, shortCount] for one round — 0/0 when nobody has called it yet. */
    private int[] sideCounts(Long assetId, String timeframe, Instant openTime) {
        List<Object[]> rows = livePredictionRepository.countSides(assetId, timeframe, openTime);
        if (rows.isEmpty()) {
            return new int[]{0, 0};
        }
        Object[] row = rows.get(0);
        return new int[]{((Number) row[0]).intValue(), ((Number) row[1]).intValue()};
    }

    public LiveRoundHistoryResponse history(String assetSymbol) {
        Asset asset = roundSelectionService.resolveAsset(assetSymbol);
        String timeframe = properties.timeframe();
        int size = properties.live().historySize();

        List<Candle> settled = candleRepository.findByAssetAndTimeframeOrderByOpenTimeDesc(
                asset, timeframe, PageRequest.of(0, size));

        /* One batched query for the whole page rather than one per row — a page of 24 used to
           mean 24 extra round-trips on a public, unauthenticated endpoint. Rounds nobody called
           are simply absent from the result and default to [0, 0] below. */
        Map<Instant, int[]> sidesByOpenTime = new HashMap<>();
        if (!settled.isEmpty()) {
            List<Instant> openTimes = settled.stream().map(Candle::getOpenTime).toList();
            for (Object[] row : livePredictionRepository.countSidesForRounds(asset.getId(), timeframe, openTimes)) {
                sidesByOpenTime.put((Instant) row[0],
                        new int[]{((Number) row[1]).intValue(), ((Number) row[2]).intValue()});
            }
        }

        List<LiveRoundHistoryResponse.Entry> entries = settled.stream().map(candle -> {
            LiveRound round = LiveRound.at(candle.getOpenTime(), timeframe, properties.live().lockBefore());
            Direction result = candle.getClose().compareTo(candle.getOpen()) >= 0 ? Direction.LONG : Direction.SHORT;
            int[] sides = sidesByOpenTime.getOrDefault(candle.getOpenTime(), new int[]{0, 0});
            return new LiveRoundHistoryResponse.Entry(round.number(), candle.getOpenTime(), round.closeAt(),
                    candle.getOpen(), candle.getClose(), result.name(), sides[0], sides[1]);
        }).toList();

        return new LiveRoundHistoryResponse(asset.getSymbol(), timeframe, entries);
    }
}

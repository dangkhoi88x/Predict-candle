package com.example.candles.service;

import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.LiveRound;
import com.example.candles.dto.response.AdminLiveRoundDetail;
import com.example.candles.dto.response.AdminLiveRounds;
import com.example.candles.entity.Asset;
import com.example.candles.entity.Candle;
import com.example.candles.entity.Direction;
import com.example.candles.entity.LivePrediction;
import com.example.candles.repository.CandleRepository;
import com.example.candles.repository.LivePredictionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The admin's side of the live game.
 *
 * The live round has more players than any other part of this app and less to look at than any
 * other: nothing stores a round, so until now the only figures anywhere were three counters on
 * the operations snapshot. This reads the rounds people actually called, and — the reason it
 * exists rather than being a nicer chart — gives an admin a way to undo one.
 *
 * A live result is a comparison against the candle the exchange confirmed, computed on every
 * read. That is what makes a bad candle unfixable from inside the game: the wrong answer is not
 * stored anywhere to correct, it is recomputed from the bad price every time anyone asks. The
 * only thing that can be changed is whether the calls exist at all.
 */
@Service
public class AdminLiveRoundService {

    private static final Logger log = LoggerFactory.getLogger(AdminLiveRoundService.class);

    /** Enough to cover a couple of days of hourly rounds without paging. */
    private static final int DEFAULT_LIMIT = 40;
    private static final int MAX_LIMIT = 200;

    private final RoundSelectionService roundSelectionService;
    private final LivePredictionRepository livePredictions;
    private final CandleRepository candles;
    private final CandlesProperties properties;
    private final Clock clock;

    public AdminLiveRoundService(RoundSelectionService roundSelectionService,
                                 LivePredictionRepository livePredictions,
                                 CandleRepository candles,
                                 CandlesProperties properties,
                                 Clock clock) {
        this.roundSelectionService = roundSelectionService;
        this.livePredictions = livePredictions;
        this.candles = candles;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AdminLiveRounds rounds(String assetSymbol, Integer limit) {
        Asset asset = roundSelectionService.resolveAsset(assetSymbol);
        String timeframe = properties.timeframe();
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);

        List<AdminLiveRounds.Round> rounds = livePredictions
                .roundsWithCalls(asset.getId(), timeframe, size).stream()
                .map(row -> {
                    Instant openTime = (Instant) row[0];
                    BigDecimal open = (BigDecimal) row[4];
                    BigDecimal close = (BigDecimal) row[5];
                    LiveRound round = round(openTime, timeframe);
                    boolean settled = open != null && close != null;
                    return new AdminLiveRounds.Round(round.number(), openTime, round.closeAt(), settled,
                            open, close, settled ? outcome(open, close).name() : null,
                            asLong(row[1]), asLong(row[2]), asLong(row[3]), asLong(row[6]));
                })
                .toList();

        return new AdminLiveRounds(asset.getSymbol(), timeframe, clock.instant(), rounds);
    }

    @Transactional(readOnly = true)
    public AdminLiveRoundDetail detail(String assetSymbol, long roundNumber) {
        Asset asset = roundSelectionService.resolveAsset(assetSymbol);
        String timeframe = properties.timeframe();
        LiveRound round = round(roundNumber, timeframe);

        Optional<Candle> settled = candles.findByAssetAndTimeframeAndOpenTime(asset, timeframe, round.openTime());
        Direction result = settled.map(c -> outcome(c.getOpen(), c.getClose())).orElse(null);

        /*
         * The same roster query the public round uses, read for different fields. That one takes
         * the display name and the shortened wallet; this one takes the address and the account
         * id, because an admin who opened this round did so to find an account, and a shortened
         * address is not something you can act on.
         */
        List<AdminLiveRoundDetail.Call> calls = livePredictions
                .findParticipants(asset.getId(), timeframe, round.openTime()).stream()
                .map(p -> new AdminLiveRoundDetail.Call(
                        p.getUser().getId(), p.getUser().getWalletAddress(), p.getUser().getDisplayName(),
                        p.getDirection().name(), p.getCreatedAt(),
                        result == null ? null : result == p.getDirection()))
                .toList();

        return new AdminLiveRoundDetail(round.number(), asset.getSymbol(), timeframe,
                round.openTime(), round.closeAt(), result != null,
                settled.map(Candle::getOpen).orElse(null), settled.map(Candle::getClose).orElse(null),
                result == null ? null : result.name(), calls);
    }

    /**
     * Removes every call on one round, and returns how many there were.
     *
     * Allowed on a round that is still open, which is the case worth allowing rather than an
     * oversight: a price feed noticed to be wrong while the round is running is exactly when an
     * admin wants the round cleared, and the players it clears can simply call it again. Voiding
     * a round nobody called is a no-op that reports zero rather than an error — the interesting
     * failure is a round that could not be identified, not one that turned out to be empty.
     */
    @Transactional
    public int voidRound(String assetSymbol, long roundNumber, Long adminId) {
        Asset asset = roundSelectionService.resolveAsset(assetSymbol);
        String timeframe = properties.timeframe();
        LiveRound round = round(roundNumber, timeframe);

        int removed = livePredictions.deleteRound(asset.getId(), timeframe, round.openTime());
        /* Logged because nothing else records it: the rows are gone and carry no tombstone, so
           this line is the only account of who removed what. */
        log.warn("Admin {} voided live round {} ({} {} at {}), removing {} call(s)",
                adminId, roundNumber, asset.getSymbol(), timeframe, round.openTime(), removed);
        return removed;
    }

    /** LONG when the candle closed at or above its open — the same test every other reader makes. */
    private static Direction outcome(BigDecimal open, BigDecimal close) {
        return close.compareTo(open) >= 0 ? Direction.LONG : Direction.SHORT;
    }

    private LiveRound round(Instant openTime, String timeframe) {
        return LiveRound.at(openTime, timeframe, properties.live().lockBefore());
    }

    private LiveRound round(long number, String timeframe) {
        return LiveRound.byNumber(number, timeframe, properties.live().lockBefore());
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}

package com.example.candles.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import com.example.candles.client.Timeframes;
import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.CandleAggregator;
import com.example.candles.entity.Asset;
import com.example.candles.entity.Candle;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.CandleRepository;

/**
 * The candles a round is played on, at whichever timeframe it is played at.
 *
 * Only one timeframe is stored ({@code candles.timeframe}, hourly). A 4h or 1d round is folded out
 * of those rows by {@link CandleAggregator} — the same bargain the trade terminal's chart makes —
 * so a longer game needs no second sync, no backfill and no gap handling of its own. Nothing
 * shorter than the stored timeframe can be offered: those minutes were never recorded, and an
 * hourly candle cannot be taken apart into the sixty that made it.
 *
 * <h2>An index is always a position in the stored candles</h2>
 *
 * {@code startIndex} means what it has always meant — rank by {@code open_time} among the stored
 * rows, counted from zero — whatever timeframe the round is at. Two things fall out of that, and
 * both are why it is worth the arithmetic:
 *
 * <ul>
 *   <li>{@code guess_results} keeps one coordinate space. Its unique constraint (user, asset,
 *       timeframe, start_index, guess, mode) still names one attempt, and a 4h round and an hourly
 *       one on the same stretch of chart are different rounds because the timeframe differs, not
 *       because the index happens to.</li>
 *   <li>A round token needs no new field: the timeframe it already carries decides how its index
 *       is read.</li>
 * </ul>
 *
 * The index of a longer round is always the first stored candle of a bucket
 * ({@link #alignToBucket}), so a bar drawn here covers the same clock period an exchange would
 * draw — periods are counted from the epoch, never from where the window happened to start.
 */
@Service
public class RoundCandleService {

    private final CandleRepository candles;
    private final AssetRepository assets;
    private final CandlesProperties properties;

    public RoundCandleService(CandleRepository candles, AssetRepository assets, CandlesProperties properties) {
        this.candles = candles;
        this.assets = assets;
        this.properties = properties;
    }

    /** The timeframe every candle is stored at, and the shortest a round can be played at. */
    public String storedTimeframe() {
        return properties.timeframe();
    }

    /** What a player may choose, in the order the picker shows them. */
    public List<String> playable() {
        return properties.round().timeframes();
    }

    /**
     * The timeframe a request asked for: absent means the stored one, which is what every caller
     * that predates this got. Anything not on the list is refused rather than quietly played at
     * another timeframe — a chart labelled 4h must be 4h.
     */
    public String resolve(String requested) {
        if (requested == null || requested.isBlank()) return storedTimeframe();
        String wanted = requested.trim().toLowerCase();
        if (!playable().contains(wanted)) {
            throw new IllegalArgumentException("Khung thời gian không chơi được: " + requested);
        }
        return wanted;
    }

    /** How many stored candles make one bar at {@code timeframe}. 1 for the stored timeframe itself. */
    public int storedPerBar(String timeframe) {
        long bar = Timeframes.parse(timeframe).toMillis();
        long stored = Timeframes.parse(storedTimeframe()).toMillis();
        if (bar < stored || bar % stored != 0) {
            throw new IllegalArgumentException("Khung " + timeframe + " không gộp được từ " + storedTimeframe());
        }
        return (int) (bar / stored);
    }

    /** Stored rows spanned by {@code bars} bars — how an index moves by a whole number of bars. */
    public int storedSpan(String timeframe, int bars) {
        return bars * storedPerBar(timeframe);
    }

    /**
     * {@code bars} candles at {@code timeframe}, starting at the stored position
     * {@code startIndex}. Shorter than asked when history runs out, which every caller already
     * treats as "not enough chart".
     */
    public List<Candle> window(Asset asset, String timeframe, int startIndex, int bars) {
        if (bars <= 0) return List.of();
        int perBar = storedPerBar(timeframe);
        List<Candle> stored = candles.findWindow(asset.getId(), storedTimeframe(),
                Math.max(0, startIndex), bars * perBar);
        if (perBar == 1) return stored;

        List<CandleAggregator.Bar> rolled = CandleAggregator.rollUp(stored.stream()
                .map(c -> new CandleAggregator.Bar(c.getOpenTime(), c.getOpen(), c.getHigh(),
                        c.getLow(), c.getClose(), c.getVolume()))
                .toList(), timeframe);

        /* The last bar is dropped when the stored read stopped inside it: a bar built from three
           of its four hours is a candle that never traded that way, and it would be the one the
           player is asked to call. Only a read that came back short can end mid-bucket. */
        int complete = stored.size() == bars * perBar ? rolled.size() : Math.max(0, rolled.size() - 1);
        List<Candle> out = new ArrayList<>(Math.min(bars, complete));
        for (int i = 0; i < Math.min(bars, complete); i++) {
            CandleAggregator.Bar bar = rolled.get(i);
            out.add(new Candle(asset, timeframe, bar.time(), bar.open(), bar.high(), bar.low(),
                    bar.close(), bar.volume()));
        }
        return List.copyOf(out);
    }

    /**
     * The same window for a caller that holds only the asset's id. The folded bars are never
     * persisted, so the reference is there to carry the pair's identity and nothing reads it.
     */
    public List<Candle> window(Long assetId, String timeframe, int startIndex, int bars) {
        if (storedPerBar(timeframe) == 1) {
            return candles.findWindow(assetId, storedTimeframe(), Math.max(0, startIndex), bars);
        }
        return window(assets.getReferenceById(assetId), timeframe, startIndex, bars);
    }

    /** One bar, {@code bars} bars after {@code startIndex}. */
    public Candle at(Asset asset, String timeframe, int startIndex, int barOffset) {
        List<Candle> window = window(asset, timeframe, startIndex + storedSpan(timeframe, barOffset), 1);
        if (window.isEmpty()) {
            throw new IllegalStateException("Candle at index " + startIndex + " (+" + barOffset
                    + " bars of " + timeframe + ") no longer exists");
        }
        return window.getFirst();
    }

    /**
     * Moves a stored position back to the first candle of its own bucket, so a bar covers the
     * clock period an exchange would draw rather than starting wherever the draw landed.
     *
     * The bucket's start is a time, and the index of it is how many stored candles closed before
     * it — which is the same definition {@code findWindow}'s offset uses, so the two agree even
     * where the history has a gap in it.
     */
    public int alignToBucket(Asset asset, String timeframe, int storedIndex) {
        if (storedPerBar(timeframe) == 1) return storedIndex;
        List<Candle> one = candles.findWindow(asset.getId(), storedTimeframe(), Math.max(0, storedIndex), 1);
        if (one.isEmpty()) return storedIndex;
        return (int) candles.countByAssetAndTimeframeAndOpenTimeLessThan(asset, storedTimeframe(),
                Timeframes.currentPeriodStart(one.getFirst().getOpenTime(), timeframe));
    }
}

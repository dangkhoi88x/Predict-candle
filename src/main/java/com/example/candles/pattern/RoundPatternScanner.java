package com.example.candles.pattern;

import com.example.candles.domain.Candle;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Finds the candlestick patterns inside a round the player has just finished, so the game can
 * point at what was actually on the chart instead of leaving them to spot it themselves.
 *
 * Only patterns from {@link PatternLibrary} — the 1-3 candle kind. The chart-level patterns in
 * {@code TechnicalPatternLibrary} are matched against swing pivots and need far more room than
 * a round holds (its breakout lookahead alone is 80 candles), so running them over a window
 * this short would mostly invent shapes that are not there.
 */
@Service
public class RoundPatternScanner {

    /**
     * A chart with every doji marked is a chart with nothing marked. When there are more hits
     * than this, the ones nearest the guessing are kept — those are the candles the player was
     * actually being asked about.
     */
    private static final int MAX_HITS = 8;

    /**
     * Patterns whose last candle falls in {@code [fromIndex, toIndex)}. The window itself may
     * reach back before {@code fromIndex}: a three-candle pattern completing on the first
     * candle the player saw is still a pattern they were looking at.
     */
    public List<PatternHit> scan(List<Candle> candles, int fromIndex, int toIndex) {
        List<PatternHit> hits = new ArrayList<>();

        for (Map.Entry<String, PatternDefinition> entry : PatternLibrary.all().entrySet()) {
            PatternDefinition definition = entry.getValue();
            int size = definition.windowSize();
            for (int end = Math.max(size, fromIndex + 1); end <= Math.min(toIndex, candles.size()); end++) {
                if (definition.matcher().matches(candles.subList(end - size, end))) {
                    hits.add(new PatternHit(entry.getKey(), end - size, size));
                }
            }
        }

        hits.sort(Comparator.comparingInt(h -> h.startIndex() + h.length()));
        if (hits.size() > MAX_HITS) {
            hits = new ArrayList<>(hits.subList(hits.size() - MAX_HITS, hits.size()));
        }
        return List.copyOf(hits);
    }

    /** {@code startIndex} is into the same list that was scanned; the pattern spans {@code length} candles. */
    public record PatternHit(String patternId, int startIndex, int length) {
    }
}

package com.example.candles.pattern;

import com.example.candles.domain.Candle;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.example.candles.pattern.CandleMetrics.body;
import static com.example.candles.pattern.CandleMetrics.bullish;
import static com.example.candles.pattern.CandleMetrics.cl;
import static com.example.candles.pattern.CandleMetrics.lowerWick;
import static com.example.candles.pattern.CandleMetrics.o;
import static com.example.candles.pattern.CandleMetrics.range;
import static com.example.candles.pattern.CandleMetrics.upperWick;

/**
 * Registry of the candlestick patterns shown in the "Mẫu Nến" library, matched against a
 * (usually 1-3 candle) window. Thresholds are pragmatic approximations tuned for continuous
 * 24/7 crypto trading (no overnight gaps), not textbook-exact TA rules.
 */
public final class PatternLibrary {

    private static final Map<String, PatternDefinition> DEFINITIONS = new LinkedHashMap<>();

    static {
        DEFINITIONS.put("doji", new PatternDefinition(1, window -> {
            Candle c = window.get(0);
            double range = range(c);
            return range > 0 && body(c) <= 0.1 * range;
        }));

        DEFINITIONS.put("hammer", new PatternDefinition(3, window -> {
            Candle c0 = window.get(0), c1 = window.get(1), c = window.get(2);
            boolean downtrend = cl(c1) < cl(c0) && cl(c) < cl(c1);
            double body = body(c);
            if (!downtrend || body <= 0 || range(c) <= 0) return false;
            return lowerWick(c) >= 2 * body && upperWick(c) <= 0.3 * body;
        }));

        DEFINITIONS.put("hanging-man", new PatternDefinition(3, window -> {
            Candle c0 = window.get(0), c1 = window.get(1), c = window.get(2);
            boolean uptrend = cl(c1) > cl(c0) && cl(c) > cl(c1);
            double body = body(c);
            if (!uptrend || body <= 0 || range(c) <= 0) return false;
            return lowerWick(c) >= 2 * body && upperWick(c) <= 0.3 * body;
        }));

        DEFINITIONS.put("shooting-star", new PatternDefinition(3, window -> {
            Candle c0 = window.get(0), c1 = window.get(1), c = window.get(2);
            boolean uptrend = cl(c1) > cl(c0) && cl(c) > cl(c1);
            double body = body(c);
            if (!uptrend || body <= 0 || range(c) <= 0) return false;
            return upperWick(c) >= 2 * body && lowerWick(c) <= 0.3 * body;
        }));

        DEFINITIONS.put("marubozu", new PatternDefinition(1, window -> {
            Candle c = window.get(0);
            double range = range(c);
            if (range <= 0) return false;
            return body(c) >= 0.9 * range;
        }));

        DEFINITIONS.put("bullish-engulfing", new PatternDefinition(2, window -> {
            Candle a = window.get(0), b = window.get(1);
            if (bullish(a) || !bullish(b)) return false;
            return o(b) <= cl(a) && cl(b) >= o(a) && body(b) > body(a);
        }));

        DEFINITIONS.put("bearish-engulfing", new PatternDefinition(2, window -> {
            Candle a = window.get(0), b = window.get(1);
            if (!bullish(a) || bullish(b)) return false;
            return o(b) >= cl(a) && cl(b) <= o(a) && body(b) > body(a);
        }));

        DEFINITIONS.put("piercing-line", new PatternDefinition(2, window -> {
            Candle a = window.get(0), b = window.get(1);
            if (bullish(a) || !bullish(b) || body(a) <= 0) return false;
            double mid = (o(a) + cl(a)) / 2;
            return o(b) <= o(a) && cl(b) > mid && cl(b) < o(a);
        }));

        DEFINITIONS.put("dark-cloud-cover", new PatternDefinition(2, window -> {
            Candle a = window.get(0), b = window.get(1);
            if (!bullish(a) || bullish(b) || body(a) <= 0) return false;
            double mid = (o(a) + cl(a)) / 2;
            return o(b) >= o(a) && cl(b) < mid && cl(b) > o(a);
        }));

        DEFINITIONS.put("morning-star", new PatternDefinition(3, window -> {
            Candle a = window.get(0), b = window.get(1), c = window.get(2);
            if (bullish(a) || body(a) <= 0 || body(b) > 0.35 * body(a) || !bullish(c)) return false;
            double mid = (o(a) + cl(a)) / 2;
            return cl(c) > mid;
        }));

        DEFINITIONS.put("evening-star", new PatternDefinition(3, window -> {
            Candle a = window.get(0), b = window.get(1), c = window.get(2);
            if (!bullish(a) || body(a) <= 0 || body(b) > 0.35 * body(a) || bullish(c)) return false;
            double mid = (o(a) + cl(a)) / 2;
            return cl(c) < mid;
        }));

        DEFINITIONS.put("three-white-soldiers", new PatternDefinition(3, window -> {
            Candle a = window.get(0), b = window.get(1), c = window.get(2);
            if (!bullish(a) || !bullish(b) || !bullish(c)) return false;
            if (!(cl(b) > cl(a) && cl(c) > cl(b))) return false;
            if (!(o(b) >= o(a) && o(b) <= cl(a))) return false;
            if (!(o(c) >= o(b) && o(c) <= cl(b))) return false;
            if (body(a) <= 0 || body(b) <= 0 || body(c) <= 0) return false;
            return upperWick(a) <= 0.3 * body(a) && upperWick(b) <= 0.3 * body(b) && upperWick(c) <= 0.3 * body(c);
        }));

        DEFINITIONS.put("three-black-crows", new PatternDefinition(3, window -> {
            Candle a = window.get(0), b = window.get(1), c = window.get(2);
            if (bullish(a) || bullish(b) || bullish(c)) return false;
            if (!(cl(b) < cl(a) && cl(c) < cl(b))) return false;
            if (!(o(b) <= o(a) && o(b) >= cl(a))) return false;
            if (!(o(c) <= o(b) && o(c) >= cl(b))) return false;
            if (body(a) <= 0 || body(b) <= 0 || body(c) <= 0) return false;
            return lowerWick(a) <= 0.3 * body(a) && lowerWick(b) <= 0.3 * body(b) && lowerWick(c) <= 0.3 * body(c);
        }));
    }

    private PatternLibrary() {
    }

    public static PatternDefinition get(String patternId) {
        return DEFINITIONS.get(patternId);
    }

    /** Every pattern in the library, in the order the "Mẫu Nến" tab lists them. */
    public static Map<String, PatternDefinition> all() {
        return Collections.unmodifiableMap(DEFINITIONS);
    }
}

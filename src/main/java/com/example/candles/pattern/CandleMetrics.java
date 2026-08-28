package com.example.candles.pattern;

import com.example.candles.domain.Candle;

/**
 * Cheap double-based OHLC measurements shared by the pattern matchers. Precision doesn't
 * matter here — these only drive shape detection for illustration, not trading decisions.
 */
final class CandleMetrics {

    private CandleMetrics() {
    }

    static double o(Candle c) {
        return c.getOpen().doubleValue();
    }

    static double h(Candle c) {
        return c.getHigh().doubleValue();
    }

    static double l(Candle c) {
        return c.getLow().doubleValue();
    }

    static double cl(Candle c) {
        return c.getClose().doubleValue();
    }

    static double body(Candle c) {
        return Math.abs(cl(c) - o(c));
    }

    static double range(Candle c) {
        return h(c) - l(c);
    }

    static double upperWick(Candle c) {
        return h(c) - Math.max(o(c), cl(c));
    }

    static double lowerWick(Candle c) {
        return Math.min(o(c), cl(c)) - l(c);
    }

    static boolean bullish(Candle c) {
        return cl(c) >= o(c);
    }
}

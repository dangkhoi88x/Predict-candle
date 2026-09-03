package com.example.candles.pattern;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.List;

import com.example.candles.entity.Candle;

import static com.example.candles.pattern.CandleMetrics.cl;

/**
 * Registry of the chart-level technical analysis patterns shown in the "Mẫu Hình Kỹ Thuật"
 * tab (Head & Shoulders, Double Top/Bottom, triangles, flags, wedges, cup & handle…), matched
 * against the swing-point sequence produced by {@link SwingPivotDetector} rather than a fixed
 * small candle window like {@link PatternLibrary}'s candlestick patterns. Thresholds are
 * pragmatic approximations tuned to read reasonably on continuous crypto price action, not
 * textbook-exact TA rules.
 */
public final class TechnicalPatternLibrary {

    private static final double SHOULDER_TOL = 0.07;
    private static final double HEAD_MARGIN = 0.03;
    private static final double DOUBLE_TOL = 0.03;
    private static final double BREAKOUT_MARGIN = 0.005;
    private static final double TRIANGLE_FLAT_TOL = 0.03;
    private static final double TRIANGLE_TREND_MIN = 0.02;
    private static final double WEDGE_NARROW_FACTOR = 0.85;
    private static final double FLAG_POLE_MIN_GAIN = 0.08;
    private static final int FLAG_POLE_MAX_CANDLES = 14;
    private static final double FLAG_PULLBACK_MIN = 0.1;
    private static final double FLAG_PULLBACK_MAX = 0.65;
    private static final double CUP_MIN_DEPTH = 0.08;
    private static final double CUP_RIM_TOL = 0.05;
    private static final int CUP_MIN_WIDTH_CANDLES = 15;
    private static final double BOS_TREND_MIN = 0.01;
    private static final double SFP_MIN_SWEEP = 0.001;
    private static final double SFP_MAX_SWEEP = 0.05;
    private static final double HANDLE_MAX_DEPTH_RATIO = 0.5;
    private static final int BREAKOUT_LOOKAHEAD = 80;

    private static final Map<String, TechnicalPatternDefinition> DEFINITIONS = new LinkedHashMap<>();

    static {
        DEFINITIONS.put("head-shoulders", new TechnicalPatternDefinition((candles, pivots, i) -> {
            if (!has(pivots, i, 5) || !seq(pivots, i, true, false, true, false, true)) return Optional.empty();
            SwingPoint leftShoulder = pivots.get(i), neck1 = pivots.get(i + 1), head = pivots.get(i + 2),
                    neck2 = pivots.get(i + 3), rightShoulder = pivots.get(i + 4);
            if (head.price() <= leftShoulder.price() * (1 + HEAD_MARGIN)) return Optional.empty();
            if (head.price() <= rightShoulder.price() * (1 + HEAD_MARGIN)) return Optional.empty();
            if (pctDiff(leftShoulder.price(), rightShoulder.price()) > SHOULDER_TOL) return Optional.empty();
            double neckline = (neck1.price() + neck2.price()) / 2;
            return breakoutBelow(candles, rightShoulder.index(), neckline, leftShoulder.index());
        }));

        DEFINITIONS.put("inverse-head-shoulders", new TechnicalPatternDefinition((candles, pivots, i) -> {
            if (!has(pivots, i, 5) || !seq(pivots, i, false, true, false, true, false)) return Optional.empty();
            SwingPoint leftShoulder = pivots.get(i), neck1 = pivots.get(i + 1), head = pivots.get(i + 2),
                    neck2 = pivots.get(i + 3), rightShoulder = pivots.get(i + 4);
            if (head.price() >= leftShoulder.price() * (1 - HEAD_MARGIN)) return Optional.empty();
            if (head.price() >= rightShoulder.price() * (1 - HEAD_MARGIN)) return Optional.empty();
            if (pctDiff(leftShoulder.price(), rightShoulder.price()) > SHOULDER_TOL) return Optional.empty();
            double neckline = (neck1.price() + neck2.price()) / 2;
            return breakoutAbove(candles, rightShoulder.index(), neckline, leftShoulder.index());
        }));

        DEFINITIONS.put("double-top", new TechnicalPatternDefinition((candles, pivots, i) -> {
            if (!has(pivots, i, 3) || !seq(pivots, i, true, false, true)) return Optional.empty();
            SwingPoint top1 = pivots.get(i), trough = pivots.get(i + 1), top2 = pivots.get(i + 2);
            if (pctDiff(top1.price(), top2.price()) > DOUBLE_TOL) return Optional.empty();
            return breakoutBelow(candles, top2.index(), trough.price(), top1.index());
        }));

        DEFINITIONS.put("double-bottom", new TechnicalPatternDefinition((candles, pivots, i) -> {
            if (!has(pivots, i, 3) || !seq(pivots, i, false, true, false)) return Optional.empty();
            SwingPoint bottom1 = pivots.get(i), peak = pivots.get(i + 1), bottom2 = pivots.get(i + 2);
            if (pctDiff(bottom1.price(), bottom2.price()) > DOUBLE_TOL) return Optional.empty();
            return breakoutAbove(candles, bottom2.index(), peak.price(), bottom1.index());
        }));

        DEFINITIONS.put("ascending-triangle", new TechnicalPatternDefinition((candles, pivots, i) -> {
            if (!has(pivots, i, 4) || !seq(pivots, i, true, false, true, false)) return Optional.empty();
            SwingPoint h1 = pivots.get(i), l1 = pivots.get(i + 1), h2 = pivots.get(i + 2), l2 = pivots.get(i + 3);
            if (pctDiff(h1.price(), h2.price()) > TRIANGLE_FLAT_TOL) return Optional.empty();
            if (l2.price() <= l1.price() * (1 + TRIANGLE_TREND_MIN)) return Optional.empty();
            double resistance = Math.max(h1.price(), h2.price());
            return breakoutAbove(candles, l2.index(), resistance, h1.index());
        }));

        DEFINITIONS.put("descending-triangle", new TechnicalPatternDefinition((candles, pivots, i) -> {
            if (!has(pivots, i, 4) || !seq(pivots, i, false, true, false, true)) return Optional.empty();
            SwingPoint l1 = pivots.get(i), h1 = pivots.get(i + 1), l2 = pivots.get(i + 2), h2 = pivots.get(i + 3);
            if (pctDiff(l1.price(), l2.price()) > TRIANGLE_FLAT_TOL) return Optional.empty();
            if (h2.price() >= h1.price() * (1 - TRIANGLE_TREND_MIN)) return Optional.empty();
            double support = Math.min(l1.price(), l2.price());
            return breakoutBelow(candles, h2.index(), support, l1.index());
        }));

        DEFINITIONS.put("symmetrical-triangle", new TechnicalPatternDefinition((candles, pivots, i) -> {
            if (!has(pivots, i, 4) || !seq(pivots, i, true, false, true, false)) return Optional.empty();
            SwingPoint h1 = pivots.get(i), l1 = pivots.get(i + 1), h2 = pivots.get(i + 2), l2 = pivots.get(i + 3);
            if (h2.price() >= h1.price() * (1 - TRIANGLE_TREND_MIN)) return Optional.empty();
            if (l2.price() <= l1.price() * (1 + TRIANGLE_TREND_MIN)) return Optional.empty();
            return breakoutEither(candles, l2.index(), h1.price(), l1.price(), h1.index());
        }));

        DEFINITIONS.put("bull-flag", new TechnicalPatternDefinition((candles, pivots, i) -> {
            if (!has(pivots, i, 3) || !seq(pivots, i, false, true, false)) return Optional.empty();
            SwingPoint poleStart = pivots.get(i), poleTop = pivots.get(i + 1), flagLow = pivots.get(i + 2);
            if (poleTop.index() - poleStart.index() > FLAG_POLE_MAX_CANDLES) return Optional.empty();
            double poleHeight = poleTop.price() - poleStart.price();
            if (poleHeight <= 0 || poleHeight / poleStart.price() < FLAG_POLE_MIN_GAIN) return Optional.empty();
            double pullback = (poleTop.price() - flagLow.price()) / poleHeight;
            if (pullback < FLAG_PULLBACK_MIN || pullback > FLAG_PULLBACK_MAX) return Optional.empty();
            return breakoutAbove(candles, flagLow.index(), poleTop.price(), poleStart.index());
        }));

        DEFINITIONS.put("bear-flag", new TechnicalPatternDefinition((candles, pivots, i) -> {
            if (!has(pivots, i, 3) || !seq(pivots, i, true, false, true)) return Optional.empty();
            SwingPoint poleStart = pivots.get(i), poleBottom = pivots.get(i + 1), flagHigh = pivots.get(i + 2);
            if (poleBottom.index() - poleStart.index() > FLAG_POLE_MAX_CANDLES) return Optional.empty();
            double poleHeight = poleStart.price() - poleBottom.price();
            if (poleHeight <= 0 || poleHeight / poleStart.price() < FLAG_POLE_MIN_GAIN) return Optional.empty();
            double pullback = (flagHigh.price() - poleBottom.price()) / poleHeight;
            if (pullback < FLAG_PULLBACK_MIN || pullback > FLAG_PULLBACK_MAX) return Optional.empty();
            return breakoutBelow(candles, flagHigh.index(), poleBottom.price(), poleStart.index());
        }));

        DEFINITIONS.put("rising-wedge", new TechnicalPatternDefinition((candles, pivots, i) -> {
            if (!has(pivots, i, 4) || !seq(pivots, i, false, true, false, true)) return Optional.empty();
            SwingPoint l1 = pivots.get(i), h1 = pivots.get(i + 1), l2 = pivots.get(i + 2), h2 = pivots.get(i + 3);
            if (l2.price() <= l1.price() || h2.price() <= h1.price()) return Optional.empty();
            double range1 = h1.price() - l1.price(), range2 = h2.price() - l2.price();
            if (range1 <= 0 || range2 <= 0 || range2 > range1 * WEDGE_NARROW_FACTOR) return Optional.empty();
            return breakoutBelow(candles, h2.index(), l2.price(), l1.index());
        }));

        DEFINITIONS.put("falling-wedge", new TechnicalPatternDefinition((candles, pivots, i) -> {
            if (!has(pivots, i, 4) || !seq(pivots, i, true, false, true, false)) return Optional.empty();
            SwingPoint h1 = pivots.get(i), l1 = pivots.get(i + 1), h2 = pivots.get(i + 2), l2 = pivots.get(i + 3);
            if (h2.price() >= h1.price() || l2.price() >= l1.price()) return Optional.empty();
            double range1 = h1.price() - l1.price(), range2 = h2.price() - l2.price();
            if (range1 <= 0 || range2 <= 0 || range2 > range1 * WEDGE_NARROW_FACTOR) return Optional.empty();
            return breakoutAbove(candles, l2.index(), h2.price(), h1.index());
        }));

        DEFINITIONS.put("cup-and-handle", new TechnicalPatternDefinition((candles, pivots, i) -> {
            if (!has(pivots, i, 4) || !seq(pivots, i, true, false, true, false)) return Optional.empty();
            SwingPoint rim1 = pivots.get(i), cupBottom = pivots.get(i + 1), rim2 = pivots.get(i + 2), handleLow = pivots.get(i + 3);
            if (pctDiff(rim1.price(), rim2.price()) > CUP_RIM_TOL) return Optional.empty();
            if (cupBottom.index() - rim1.index() < CUP_MIN_WIDTH_CANDLES) return Optional.empty();
            double rimAvg = (rim1.price() + rim2.price()) / 2;
            double cupDepth = (rimAvg - cupBottom.price()) / rimAvg;
            if (cupDepth < CUP_MIN_DEPTH) return Optional.empty();
            double handleDepth = (rim2.price() - handleLow.price()) / rim2.price();
            if (handleDepth <= 0 || handleDepth > cupDepth * HANDLE_MAX_DEPTH_RATIO) return Optional.empty();
            return breakoutAbove(candles, handleLow.index(), Math.max(rim1.price(), rim2.price()), rim1.index());
        }));

        DEFINITIONS.put("bos-bearish", new TechnicalPatternDefinition((candles, pivots, i) -> {
            if (!has(pivots, i, 5) || !seq(pivots, i, false, true, false, true, false)) return Optional.empty();
            SwingPoint low0 = pivots.get(i), high1 = pivots.get(i + 1), low1 = pivots.get(i + 2),
                    high2 = pivots.get(i + 3), higherLow = pivots.get(i + 4);
            if (high2.price() <= high1.price() * (1 + BOS_TREND_MIN)) return Optional.empty();
            if (higherLow.price() <= low1.price() * (1 + BOS_TREND_MIN)) return Optional.empty();
            return breakoutBelow(candles, higherLow.index(), higherLow.price(), low0.index());
        }));

        DEFINITIONS.put("bos-bullish", new TechnicalPatternDefinition((candles, pivots, i) -> {
            if (!has(pivots, i, 5) || !seq(pivots, i, true, false, true, false, true)) return Optional.empty();
            SwingPoint high0 = pivots.get(i), low1 = pivots.get(i + 1), high1 = pivots.get(i + 2),
                    low2 = pivots.get(i + 3), lowerHigh = pivots.get(i + 4);
            if (low2.price() >= low1.price() * (1 - BOS_TREND_MIN)) return Optional.empty();
            if (lowerHigh.price() >= high1.price() * (1 - BOS_TREND_MIN)) return Optional.empty();
            return breakoutAbove(candles, lowerHigh.index(), lowerHigh.price(), high0.index());
        }));

        DEFINITIONS.put("sfp-bullish", new TechnicalPatternDefinition((candles, pivots, i) -> {
            if (!has(pivots, i, 3) || !seq(pivots, i, false, true, false)) return Optional.empty();
            SwingPoint low1 = pivots.get(i), high1 = pivots.get(i + 1), low2 = pivots.get(i + 2);
            double undercut = (low1.price() - low2.price()) / low1.price();
            if (undercut <= SFP_MIN_SWEEP || undercut > SFP_MAX_SWEEP) return Optional.empty();
            return breakoutAbove(candles, low2.index(), low1.price(), low1.index());
        }));

        DEFINITIONS.put("sfp-bearish", new TechnicalPatternDefinition((candles, pivots, i) -> {
            if (!has(pivots, i, 3) || !seq(pivots, i, true, false, true)) return Optional.empty();
            SwingPoint high1 = pivots.get(i), low1 = pivots.get(i + 1), high2 = pivots.get(i + 2);
            double overshoot = (high2.price() - high1.price()) / high1.price();
            if (overshoot <= SFP_MIN_SWEEP || overshoot > SFP_MAX_SWEEP) return Optional.empty();
            return breakoutBelow(candles, high2.index(), high1.price(), high1.index());
        }));
    }

    private TechnicalPatternLibrary() {
    }

    public static TechnicalPatternDefinition get(String patternId) {
        return DEFINITIONS.get(patternId);
    }

    private static boolean has(List<SwingPoint> pivots, int i, int count) {
        return i + count <= pivots.size();
    }

    private static boolean seq(List<SwingPoint> pivots, int i, boolean... highFlags) {
        for (int k = 0; k < highFlags.length; k++) {
            if (pivots.get(i + k).high() != highFlags[k]) return false;
        }
        return true;
    }

    private static double pctDiff(double a, double b) {
        double avg = (a + b) / 2;
        return avg == 0 ? 0 : Math.abs(a - b) / avg;
    }

    private static Optional<int[]> breakoutAbove(List<Candle> candles, int fromIndex, double level, int patternStart) {
        double threshold = level * (1 + BREAKOUT_MARGIN);
        int limit = Math.min(candles.size(), fromIndex + 1 + BREAKOUT_LOOKAHEAD);
        for (int j = fromIndex + 1; j < limit; j++) {
            if (cl(candles.get(j)) > threshold) {
                return Optional.of(new int[]{patternStart, j + 1});
            }
        }
        return Optional.empty();
    }

    private static Optional<int[]> breakoutBelow(List<Candle> candles, int fromIndex, double level, int patternStart) {
        double threshold = level * (1 - BREAKOUT_MARGIN);
        int limit = Math.min(candles.size(), fromIndex + 1 + BREAKOUT_LOOKAHEAD);
        for (int j = fromIndex + 1; j < limit; j++) {
            if (cl(candles.get(j)) < threshold) {
                return Optional.of(new int[]{patternStart, j + 1});
            }
        }
        return Optional.empty();
    }

    private static Optional<int[]> breakoutEither(List<Candle> candles, int fromIndex, double upperLevel, double lowerLevel, int patternStart) {
        double upThreshold = upperLevel * (1 + BREAKOUT_MARGIN);
        double downThreshold = lowerLevel * (1 - BREAKOUT_MARGIN);
        int limit = Math.min(candles.size(), fromIndex + 1 + BREAKOUT_LOOKAHEAD);
        for (int j = fromIndex + 1; j < limit; j++) {
            double price = cl(candles.get(j));
            if (price > upThreshold || price < downThreshold) {
                return Optional.of(new int[]{patternStart, j + 1});
            }
        }
        return Optional.empty();
    }
}

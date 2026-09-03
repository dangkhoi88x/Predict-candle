package com.example.candles.pattern;

import java.util.ArrayList;
import java.util.List;

import com.example.candles.entity.Candle;

import static com.example.candles.pattern.CandleMetrics.cl;

/**
 * Simplified ZigZag swing-point detector: walks the close-price series and records a pivot
 * every time price reverses by at least {@code deviationPct} from the running extreme. Chart
 * patterns (Head & Shoulders, triangles, flags…) are defined in terms of these alternating
 * high/low swing points rather than individual candles, unlike the 1-3 candle candlestick
 * patterns in {@link PatternLibrary}.
 */
public final class SwingPivotDetector {

    private SwingPivotDetector() {
    }

    public static List<SwingPoint> detect(List<Candle> candles, double deviationPct) {
        List<SwingPoint> pivots = new ArrayList<>();
        int n = candles.size();
        if (n < 3) return pivots;

        Boolean up = null;
        int extremeIdx = 0;
        double extremePrice = cl(candles.get(0));
        double anchorPrice = extremePrice;

        for (int i = 1; i < n; i++) {
            double price = cl(candles.get(i));
            if (up == null) {
                double change = (price - anchorPrice) / anchorPrice;
                if (Math.abs(change) >= deviationPct) {
                    up = change > 0;
                    extremeIdx = i;
                    extremePrice = price;
                }
                continue;
            }
            if (up) {
                if (price > extremePrice) {
                    extremePrice = price;
                    extremeIdx = i;
                } else if ((extremePrice - price) / extremePrice >= deviationPct) {
                    pivots.add(new SwingPoint(extremeIdx, extremePrice, true));
                    up = false;
                    extremeIdx = i;
                    extremePrice = price;
                }
            } else {
                if (price < extremePrice) {
                    extremePrice = price;
                    extremeIdx = i;
                } else if ((price - extremePrice) / extremePrice >= deviationPct) {
                    pivots.add(new SwingPoint(extremeIdx, extremePrice, false));
                    up = true;
                    extremeIdx = i;
                    extremePrice = price;
                }
            }
        }
        if (up != null) {
            pivots.add(new SwingPoint(extremeIdx, extremePrice, up));
        }
        return pivots;
    }
}

package com.example.candles.pattern;

/**
 * A local price extreme (swing high or swing low) detected by {@link SwingPivotDetector}.
 * {@code index} is the candle's position in the scanned history list.
 */
public record SwingPoint(int index, double price, boolean high) {
}

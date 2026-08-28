package com.example.candles.round;

import com.example.candles.domain.Asset;
import com.example.candles.domain.Candle;

import java.util.List;

/**
 * A freshly picked chart: the candles shown to the player and where this window starts in
 * the asset's full history (needed to look up each subsequent guess's answer candle).
 */
public record RoundSelection(Asset asset, String timeframe, int startIndex, List<Candle> visibleCandles) {
}

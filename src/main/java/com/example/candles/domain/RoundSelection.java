package com.example.candles.domain;

import java.util.List;

import com.example.candles.entity.Asset;
import com.example.candles.entity.Candle;

/**
 * A freshly picked chart: the candles shown to the player and where this window starts in
 * the asset's full history (needed to look up each subsequent guess's answer candle).
 */
public record RoundSelection(Asset asset, String timeframe, int startIndex, List<Candle> visibleCandles) {
}

package com.example.candles.dto.response;

import java.util.List;

/**
 * Recent history for the terminal's chart.
 *
 * Timestamps are included here, unlike the game's own rounds — the whole point of a trading
 * chart is knowing when you are looking at, and there is no answer to give away.
 */
public record DemoChartResponse(String asset, String timeframe, List<DatedCandleDto> candles) {
}

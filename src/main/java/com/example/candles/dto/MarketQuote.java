package com.example.candles.dto;

import java.math.BigDecimal;
import java.util.List;

public record MarketQuote(
        String symbol,
        String name,
        String sector,
        BigDecimal price,
        BigDecimal changePercent,
        long marketCap,
        List<BigDecimal> sparkline
) {
}

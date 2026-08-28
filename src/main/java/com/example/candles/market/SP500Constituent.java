package com.example.candles.market;

/**
 * A curated S&P 500 company for the heatmap. {@code approxMarketCapUsd} is a rough, static
 * figure used only to size treemap tiles — live price and % change come from Yahoo Finance
 * at request time, so the tile's color/number are always current even though its size drifts
 * slowly out of date. {@code sector} groups constituents for the sector drill-down view; this
 * is a curated subset, not the full index, so sector aggregates are illustrative only.
 */
public record SP500Constituent(String symbol, String name, long approxMarketCapUsd, String sector) {

    public static final java.util.List<SP500Constituent> ALL = java.util.List.of(
            new SP500Constituent("AAPL", "Apple", 3_500_000_000_000L, "Technology"),
            new SP500Constituent("NVDA", "Nvidia", 3_300_000_000_000L, "Technology"),
            new SP500Constituent("MSFT", "Microsoft", 3_100_000_000_000L, "Technology"),
            new SP500Constituent("AVGO", "Broadcom", 800_000_000_000L, "Technology"),
            new SP500Constituent("GOOGL", "Alphabet", 2_100_000_000_000L, "Communication Services"),
            new SP500Constituent("META", "Meta Platforms", 1_400_000_000_000L, "Communication Services"),
            new SP500Constituent("AMZN", "Amazon", 2_000_000_000_000L, "Consumer Discretionary"),
            new SP500Constituent("TSLA", "Tesla", 800_000_000_000L, "Consumer Discretionary"),
            new SP500Constituent("HD", "Home Depot", 370_000_000_000L, "Consumer Discretionary"),
            new SP500Constituent("BRK-B", "Berkshire Hathaway", 950_000_000_000L, "Financials"),
            new SP500Constituent("JPM", "JPMorgan Chase", 650_000_000_000L, "Financials"),
            new SP500Constituent("V", "Visa", 550_000_000_000L, "Financials"),
            new SP500Constituent("MA", "Mastercard", 470_000_000_000L, "Financials"),
            new SP500Constituent("LLY", "Eli Lilly", 750_000_000_000L, "Health Care"),
            new SP500Constituent("UNH", "UnitedHealth", 500_000_000_000L, "Health Care"),
            new SP500Constituent("JNJ", "Johnson & Johnson", 380_000_000_000L, "Health Care"),
            new SP500Constituent("WMT", "Walmart", 600_000_000_000L, "Consumer Staples"),
            new SP500Constituent("COST", "Costco", 400_000_000_000L, "Consumer Staples"),
            new SP500Constituent("PG", "Procter & Gamble", 400_000_000_000L, "Consumer Staples"),
            new SP500Constituent("XOM", "ExxonMobil", 480_000_000_000L, "Energy"),
            new SP500Constituent("CAT", "Caterpillar", 190_000_000_000L, "Industrials"),
            new SP500Constituent("NEE", "NextEra Energy", 150_000_000_000L, "Utilities"),
            new SP500Constituent("LIN", "Linde", 210_000_000_000L, "Materials"),
            new SP500Constituent("PLD", "Prologis", 100_000_000_000L, "Real Estate")
    );
}

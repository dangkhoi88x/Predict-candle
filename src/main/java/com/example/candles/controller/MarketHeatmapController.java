package com.example.candles.controller;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.Duration;
import java.util.List;

import com.example.candles.client.SP500Constituent;
import com.example.candles.client.YahooFinanceClient;
import com.example.candles.dto.response.MarketQuote;

@RestController
@RequestMapping("/api/market")
public class MarketHeatmapController {

    private static final String CACHE_KEY = "sp500";

    private final YahooFinanceClient yahooFinanceClient;
    private final Cache<String, List<MarketQuote>> cache;

    public MarketHeatmapController(YahooFinanceClient yahooFinanceClient) {
        this.yahooFinanceClient = yahooFinanceClient;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(60))
                .build();
    }

    @GetMapping("/sp500")
    public List<MarketQuote> sp500() {
        return cache.get(CACHE_KEY, key -> yahooFinanceClient.fetchQuotes(SP500Constituent.ALL));
    }
}

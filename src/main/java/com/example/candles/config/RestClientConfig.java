package com.example.candles.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient binanceRestClient(CandlesProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.binance().baseUrl())
                .build();
    }

    @Bean
    public RestClient yahooFinanceRestClient() {
        return RestClient.builder()
                .baseUrl("https://query1.finance.yahoo.com")
                .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build();
    }
}

package com.example.candles.config;

import org.springframework.beans.factory.annotation.Value;
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

    /** Built whichever source is selected — a client nobody calls costs nothing. */
    @Bean
    public RestClient okxRestClient(@Value("${candles.okx.base-url:https://www.okx.com}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
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

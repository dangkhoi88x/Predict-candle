package com.example.candles.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "candles.auth")
public record AuthProperties(Jwt jwt, Cookie cookie) {

    public record Jwt(String secret, Duration accessTokenTtl, Duration refreshTokenTtl) {
    }

    public record Cookie(boolean secure) {
    }
}

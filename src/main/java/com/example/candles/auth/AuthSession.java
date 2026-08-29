package com.example.candles.auth;

public record AuthSession(AuthResponse response, String refreshToken) {
}

package com.example.candles.domain;

import com.example.candles.dto.AuthResponse;

public record AuthSession(AuthResponse response, String refreshToken) {
}

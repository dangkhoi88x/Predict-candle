package com.example.candles.domain;

import com.example.candles.dto.response.AuthResponse;

public record AuthSession(AuthResponse response, String refreshToken) {
}

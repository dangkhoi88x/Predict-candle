package com.example.candles.auth;

import com.example.candles.domain.User;

public record AuthResponse(Long userId, String walletAddress, String displayName, String accessToken) {

    public static AuthResponse from(User user, String accessToken) {
        return new AuthResponse(user.getId(), user.getWalletAddress(), user.getDisplayName(), accessToken);
    }
}

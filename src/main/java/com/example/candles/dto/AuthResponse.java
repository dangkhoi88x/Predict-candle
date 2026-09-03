package com.example.candles.dto;

import com.example.candles.entity.User;

/**
 * {@code role} is here so the interface knows whether to offer the admin page at all. It is a
 * hint for drawing, never a permission: the server decides again on every request.
 */
public record AuthResponse(Long userId, String walletAddress, String displayName, String role,
                            String accessToken) {

    public static AuthResponse from(User user, String accessToken) {
        return new AuthResponse(user.getId(), user.getWalletAddress(), user.getDisplayName(),
                user.getRole().name(), accessToken);
    }
}

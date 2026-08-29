package com.example.candles.auth;

public record WalletNonceResponse(String address, String nonce, String message) {
}

package com.example.candles.exception;

public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Xác thực chữ ký ví thất bại hoặc phiên đã hết hạn.");
    }

    /** For a sign-in that is not a wallet's, whose failure should not talk about a wallet. */
    public InvalidCredentialsException(String message) {
        super(message);
    }
}

package com.example.candles.auth;

public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Xác thực chữ ký ví thất bại hoặc phiên đã hết hạn.");
    }
}

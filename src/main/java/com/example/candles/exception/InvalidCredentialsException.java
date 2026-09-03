package com.example.candles.exception;

public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Xác thực chữ ký ví thất bại hoặc phiên đã hết hạn.");
    }
}

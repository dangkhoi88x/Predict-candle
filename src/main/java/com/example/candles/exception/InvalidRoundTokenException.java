package com.example.candles.exception;

public class InvalidRoundTokenException extends RuntimeException {

    public InvalidRoundTokenException(Throwable cause) {
        super("Invalid or expired round token", cause);
    }
}

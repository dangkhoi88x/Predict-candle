package com.example.candles.api;

/** Caller went past their per-minute allowance; answered with 429. */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}

package com.example.candles.exception;

/** A guess that arrived outside the window the shot clock allows — too late, or too fast to be human. */
public class GuessOutOfTimeException extends RuntimeException {

    public GuessOutOfTimeException(String message) {
        super(message);
    }
}

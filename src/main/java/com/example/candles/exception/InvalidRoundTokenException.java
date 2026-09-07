package com.example.candles.exception;

public class InvalidRoundTokenException extends RuntimeException {

    public InvalidRoundTokenException(Throwable cause) {
        super("Invalid or expired round token", cause);
    }

    /**
     * A token that verified but is not the one this endpoint asked for — the daily round having
     * rolled over to a new day, or a token minted for the other game. The signature was fine, so
     * "invalid or expired" would send the player looking for the wrong problem.
     */
    public InvalidRoundTokenException(String message) {
        super(message);
    }
}

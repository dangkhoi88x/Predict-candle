package com.example.candles.api;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code direction} is absent when the countdown ran out — the client saying "no answer" so
 * the round can move on. Anything else has to be LONG or SHORT.
 */
public record GuessRequest(
        @NotBlank String roundToken,
        String direction
) {

    public boolean answered() {
        return direction != null && !direction.isBlank();
    }
}

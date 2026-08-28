package com.example.candles.api;

import jakarta.validation.constraints.NotBlank;

public record GuessRequest(
        @NotBlank String roundToken,
        @NotBlank String direction
) {
}

package com.example.candles.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * One answer to the day's pattern quiz. The id is checked against the day's own four choices
 * before anything is recorded, so a client cannot answer with a pattern it was never offered.
 */
public record PatternGuessRequest(@NotBlank String patternId) {
}

package com.example.candles.dto.request;

import jakarta.validation.constraints.NotBlank;

/** The signed result a finished practice chart handed back ({@code GuessResponse.challengeToken}). */
public record ChallengeCreateRequest(@NotBlank String challengeToken) {
}

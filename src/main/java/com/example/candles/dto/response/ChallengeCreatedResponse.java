package com.example.candles.dto.response;

/** {@code path} is what goes after the site's origin in the link to share. */
public record ChallengeCreatedResponse(String id, String path, int correct, int total) {
}

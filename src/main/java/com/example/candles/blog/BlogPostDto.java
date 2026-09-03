package com.example.candles.blog;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * What a post looks like over the wire. {@code tags} and {@code body} are JsonNode so they
 * travel as real JSON rather than a string containing JSON — the browser gets the same shape
 * the old hard-coded POSTS array had, which is what lets blog.js render either source with
 * one code path.
 */
public record BlogPostDto(
        Long id,
        String slug,
        String title,
        JsonNode tags,
        String source,
        String sourceUrl,
        String imageCredit,
        String coverSvg,
        String coverImg,
        JsonNode body,
        boolean published,
        int position,
        Instant createdAt,
        Instant updatedAt
) {
}

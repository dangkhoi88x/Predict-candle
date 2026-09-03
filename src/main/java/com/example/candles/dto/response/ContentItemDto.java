package com.example.candles.dto.response;

import tools.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * {@code body} travels as real JSON so the front end receives the same object shape the
 * hard-coded arrays used to hold, and {@code editable} tells the admin screen whether this
 * entry's key can be changed or the entry removed — see {@link ContentKind}.
 */
public record ContentItemDto(
        Long id,
        String kind,
        String itemKey,
        String title,
        JsonNode body,
        int position,
        boolean published,
        boolean editableKey,
        Instant createdAt,
        Instant updatedAt
) {
}

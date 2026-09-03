package com.example.candles.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public record ContentItemRequest(
        @Size(max = 80) String itemKey,
        @NotBlank @Size(max = 300) String title,
        JsonNode body,
        Integer position,
        boolean published
) {
}

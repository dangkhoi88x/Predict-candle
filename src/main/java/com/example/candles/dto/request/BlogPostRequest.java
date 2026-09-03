package com.example.candles.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * A post as the editor submits it. {@code slug} is optional — left out, the service derives
 * one from the title, which is what the "new post" form does.
 */
public record BlogPostRequest(
        @Size(max = 180) String slug,
        @NotBlank @Size(max = 300) String title,
        JsonNode tags,
        @Size(max = 300) String source,
        @Size(max = 600) String sourceUrl,
        String imageCredit,
        String coverSvg,
        @Size(max = 600) String coverImg,
        JsonNode body,
        boolean published,
        Integer position
) {
}

package com.example.candles.dto.response;

/**
 * What the static page needs to know about the deployment it is running on, and cannot be told
 * any other way: index.html is a file, not a template.
 *
 * @param goatcounter the GoatCounter endpoint to count against, or null to count nothing —
 *                    which is every checkout, so a developer's clicks never land in the demo's
 *                    numbers
 */
public record SiteConfigResponse(String goatcounter) {
}

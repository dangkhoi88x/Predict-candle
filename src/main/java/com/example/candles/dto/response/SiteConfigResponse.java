package com.example.candles.dto.response;

/**
 * What the static page needs to know about the deployment it is running on, and cannot be told
 * any other way: index.html is a file, not a template.
 *
 * @param telegram    what the page needs to behave as a Telegram Mini App, or null
 * @param goatcounter the GoatCounter endpoint to count against, or null to count nothing —
 *                    which is every checkout, so a developer's clicks never land in the demo's
 *                    numbers
 */
public record SiteConfigResponse(String goatcounter, Telegram telegram) {

    /**
     * Null when no bot is configured. {@code appLink} is the t.me link that opens the Mini App
     * ({@code https://t.me/<bot>/<app>}), used to share a challenge that opens inside Telegram;
     * null when the app's short name is not configured, in which case links stay web links.
     */
    public record Telegram(boolean login, String appLink) {
    }
}

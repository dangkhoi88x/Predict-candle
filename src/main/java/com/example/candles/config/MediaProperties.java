package com.example.candles.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;

/**
 * @param adminWallets wallet addresses allowed to upload and delete media. Empty means
 *                     nobody can — the endpoints are shut rather than open, because an
 *                     unset allowlist is far more likely to be an oversight than an
 *                     intention to let the whole internet write to the media account.
 */
@ConfigurationProperties(prefix = "candles.media")
public record MediaProperties(long maxImageSizeBytes, Cloudinary cloudinary, List<String> adminWallets) {

    public MediaProperties {
        // Wallet addresses are case-insensitive; store them folded so the check does not
        // depend on whoever wrote the config matching the casing the wallet reports.
        adminWallets = adminWallets == null ? List.of()
                : adminWallets.stream().filter(a -> a != null && !a.isBlank())
                        .map(a -> a.trim().toLowerCase(Locale.ROOT)).toList();
    }

    public boolean isMediaAdmin(String walletAddress) {
        return walletAddress != null
                && adminWallets.contains(walletAddress.trim().toLowerCase(Locale.ROOT));
    }

    public record Cloudinary(String cloudName, String apiKey, String apiSecret) {
        public boolean isConfigured() {
            return cloudName != null && !cloudName.isBlank()
                    && apiKey != null && !apiKey.isBlank()
                    && apiSecret != null && !apiSecret.isBlank();
        }
    }
}

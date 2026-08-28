package com.example.candles.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "candles.media")
public record MediaProperties(long maxImageSizeBytes, Cloudinary cloudinary) {

    public record Cloudinary(String cloudName, String apiKey, String apiSecret) {
        public boolean isConfigured() {
            return cloudName != null && !cloudName.isBlank()
                    && apiKey != null && !apiKey.isBlank()
                    && apiSecret != null && !apiSecret.isBlank();
        }
    }
}

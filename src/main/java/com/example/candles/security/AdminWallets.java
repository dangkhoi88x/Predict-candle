package com.example.candles.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.List;

import com.example.candles.config.AdminProperties;
import com.example.candles.config.MediaProperties;

/**
 * The effective admin allowlist, resolved once at startup.
 *
 * Before roles existed, the only thing resembling an admin was candles.media.admin-wallets,
 * read by MediaController alone. That list is now honoured as a fallback so an existing
 * deployment does not silently lose its admin the moment it picks up this version — but it
 * is a stepping stone, and the warning says so.
 */
@Component
public class AdminWallets {

    private static final Logger log = LoggerFactory.getLogger(AdminWallets.class);

    private final List<String> wallets;

    public AdminWallets(AdminProperties adminProperties, MediaProperties mediaProperties) {
        if (!adminProperties.wallets().isEmpty()) {
            this.wallets = adminProperties.wallets();
        } else if (!mediaProperties.adminWallets().isEmpty()) {
            log.warn("Falling back to candles.media.admin-wallets for the admin list. "
                    + "Move these addresses to candles.admin.wallets (ADMIN_WALLETS) — the media "
                    + "property now only exists for this fallback.");
            this.wallets = mediaProperties.adminWallets();
        } else {
            this.wallets = List.of();
        }
    }

    public boolean grantsAdmin(String walletAddress) {
        return walletAddress != null && wallets.contains(walletAddress.trim().toLowerCase(java.util.Locale.ROOT));
    }

    public List<String> all() {
        return wallets;
    }
}

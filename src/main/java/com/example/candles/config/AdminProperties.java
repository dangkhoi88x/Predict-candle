package com.example.candles.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;

/**
 * Who is an admin. Deliberately configuration rather than something editable from inside the
 * application: on an app where anyone can create an account by connecting a wallet, the set of
 * accounts that can write to shared storage should only be changeable by whoever can deploy.
 *
 * Empty means nobody is an admin and every /api/admin route is shut — the safer reading of an
 * unset value, since an empty allowlist is far more likely to be an oversight than a decision
 * to let anyone in.
 *
 * @param wallets wallet addresses to hold the ADMIN role, case-insensitive
 */
@ConfigurationProperties(prefix = "candles.admin")
public record AdminProperties(List<String> wallets) {

    public AdminProperties {
        wallets = wallets == null ? List.of()
                : wallets.stream().filter(w -> w != null && !w.isBlank())
                        .map(w -> w.trim().toLowerCase(Locale.ROOT)).toList();
    }

    public boolean grantsAdmin(String walletAddress) {
        return walletAddress != null
                && wallets.contains(walletAddress.trim().toLowerCase(Locale.ROOT));
    }
}

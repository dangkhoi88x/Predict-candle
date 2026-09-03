package com.example.candles.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * Issues a single-use nonce per wallet address so the signed "sign-in" message can't be
 * replayed. Held in memory only — a restart just forces everyone to reconnect their wallet.
 */
@Service
public class NonceService {

    private static final Duration TTL = Duration.ofMinutes(5);
    private final SecureRandom random = new SecureRandom();
    private final Cache<String, String> nonceByAddress = Caffeine.newBuilder()
            .expireAfterWrite(TTL)
            .build();

    /**
     * Returns the address's pending nonce if it still has one, and only mints a new one
     * otherwise. Wallet SDKs happily fire several account-update events for a single
     * connection, and handing each request a fresh nonce would invalidate the message the
     * user is already being asked to sign.
     */
    public String issue(String address) {
        return nonceByAddress.get(address, key -> {
            byte[] bytes = new byte[16];
            random.nextBytes(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        });
    }

    public String consume(String address) {
        String nonce = nonceByAddress.getIfPresent(address);
        if (nonce == null) {
            throw new InvalidCredentialsException();
        }
        nonceByAddress.invalidate(address);
        return nonce;
    }

    public String buildMessage(String address, String nonce) {
        return "Đăng nhập vào Candle Guess\n\nĐịa chỉ ví: " + address + "\nNonce: " + nonce;
    }
}

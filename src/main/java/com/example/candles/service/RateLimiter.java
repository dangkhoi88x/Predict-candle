package com.example.candles.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.candles.exception.TooManyRequestsException;

/**
 * Fixed-window request counting, keyed per signed-in player and per IP otherwise.
 *
 * Deliberately a counter in memory rather than anything durable: this exists to stop a script
 * walking the candle history one round at a time, not to be an accounting record. Losing the
 * counts on restart costs a minute of leniency, which is cheaper than a shared store.
 */
@Component
public class RateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final Cache<String, AtomicInteger> counters = Caffeine.newBuilder()
            .expireAfterWrite(WINDOW)
            .maximumSize(50_000)
            .build();

    public void check(String action, int limitPerMinute, HttpServletRequest request) {
        String key = action + ":" + callerKey(request);
        AtomicInteger count = counters.get(key, k -> new AtomicInteger());
        if (count.incrementAndGet() > limitPerMinute) {
            throw new TooManyRequestsException("Bạn thao tác hơi nhanh — thử lại sau một lát nhé.");
        }
    }

    /**
     * A signed-in player is one key wherever they play from. Everyone else is their address:
     * deliberately {@code getRemoteAddr()} and not X-Forwarded-For, because trusting a header
     * any client can set would hand out a fresh quota per request. Behind a proxy this needs
     * a trusted-proxy configuration first.
     */
    private String callerKey(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Long userId) {
            return "u" + userId;
        }
        return "ip" + request.getRemoteAddr();
    }
}

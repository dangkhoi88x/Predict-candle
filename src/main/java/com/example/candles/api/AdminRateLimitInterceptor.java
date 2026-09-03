package com.example.candles.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * A ceiling over the whole admin surface.
 *
 * Deliberately generous. This is not a throttle on how fast an admin may work — reordering
 * twenty pairs or deleting a folder of images should never touch it. It is a backstop against
 * the failure that actually happens: a refresh loop, a retry storm, a script left running, or a
 * leaked admin token. Anything a person does by hand stays far below it.
 *
 * It covers the paths rather than the methods on purpose. Explicit {@code rateLimiter.check}
 * calls are better where a limit is part of what an endpoint means — and the three that reach
 * Binance, reach Cloudinary or rescan guess history keep theirs — but a per-controller call is
 * a line somebody has to remember on the next admin endpoint, and this group has grown by seven
 * controllers already. A path rule covers the ones not written yet.
 *
 * Counting is per signed-in user (see {@link RateLimiter}), so one admin cannot spend another's
 * quota, and an unauthenticated caller never reaches here — SecurityConfig rejects them first.
 */
@Component
public class AdminRateLimitInterceptor implements HandlerInterceptor {

    /**
     * Four requests a second, sustained. An admin page opening every pane at once fires under
     * ten; nothing a human does approaches this.
     */
    private static final int REQUESTS_PER_MINUTE = 240;

    private final RateLimiter rateLimiter;

    public AdminRateLimitInterceptor(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        rateLimiter.check("admin", REQUESTS_PER_MINUTE, request);
        return true;
    }
}

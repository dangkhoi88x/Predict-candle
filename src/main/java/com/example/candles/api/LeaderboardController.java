package com.example.candles.api;

import com.example.candles.stats.Leaderboard;
import com.example.candles.stats.LeaderboardService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public leaderboard.
 *
 * Open to everyone, signed in or not — it is a scoreboard, and requiring an account to look at
 * one is a reason not to look. Signing in only adds the caller's own row to the response.
 *
 * Rate limited, unlike the rest of the read API. This is the one open endpoint whose cache miss
 * walks the entire guess table, so it is also the one worth putting a ceiling on.
 */
@RestController
@RequestMapping("/api/leaderboard")
public class LeaderboardController {

    private static final int REQUESTS_PER_MINUTE = 30;

    private final LeaderboardService leaderboardService;
    private final RateLimiter rateLimiter;

    public LeaderboardController(LeaderboardService leaderboardService, RateLimiter rateLimiter) {
        this.leaderboardService = leaderboardService;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public Leaderboard board(@RequestParam(defaultValue = "50") int limit,
                             Authentication authentication,
                             HttpServletRequest request) {
        rateLimiter.check("leaderboard", REQUESTS_PER_MINUTE, request);
        return leaderboardService.board(limit, callerId(authentication));
    }

    /**
     * Null for an anonymous caller rather than a rejection: everything but the {@code me} row
     * is the same for everyone, and there is nothing here worth turning a visitor away over.
     */
    private Long callerId(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof Long id ? id : null;
    }
}

package com.example.candles.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.candles.dto.response.Leaderboard;
import com.example.candles.dto.response.SeasonHistory;
import com.example.candles.service.LeaderboardService;
import com.example.candles.service.RateLimiter;

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
    private static final int SEASON_REQUESTS_PER_MINUTE = 10;

    private final LeaderboardService leaderboardService;
    private final RateLimiter rateLimiter;

    public LeaderboardController(LeaderboardService leaderboardService, RateLimiter rateLimiter) {
        this.leaderboardService = leaderboardService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * {@code season} is a month ({@code 2026-09}), {@code all} for every recorded call, or absent
     * for the season running now — which is what a visitor who follows a plain link gets.
     */
    @GetMapping
    public Leaderboard board(@RequestParam(defaultValue = "50") int limit,
                             @RequestParam(required = false) String season,
                             Authentication authentication,
                             HttpServletRequest request) {
        rateLimiter.check("leaderboard", REQUESTS_PER_MINUTE, request);
        return leaderboardService.board(limit, callerId(authentication),
                leaderboardService.resolveSeason(season));
    }

    /**
     * Finished months and the caller's medals on them — the profile's season badges, and the line
     * naming last month's winner on the board.
     *
     * Its own, lower limit: a cold read walks one ranking per month asked for, so this is the more
     * expensive of the two open endpoints here even with every season cached.
     */
    @GetMapping("/seasons")
    public SeasonHistory seasons(@RequestParam(defaultValue = "6") int months,
                                 Authentication authentication,
                                 HttpServletRequest request) {
        rateLimiter.check("leaderboard-seasons", SEASON_REQUESTS_PER_MINUTE, request);
        return leaderboardService.history(months, callerId(authentication));
    }

    /**
     * Null for an anonymous caller rather than a rejection: everything but the {@code me} row
     * is the same for everyone, and there is nothing here worth turning a visitor away over.
     */
    private Long callerId(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof Long id ? id : null;
    }
}

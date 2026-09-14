package com.example.candles.controller;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.candles.dto.request.LegacyStatsRequest;
import com.example.candles.dto.response.InsightsResponse;
import com.example.candles.dto.response.StatsResponse;
import com.example.candles.exception.InvalidCredentialsException;
import com.example.candles.service.InsightsService;
import com.example.candles.service.StatsService;

/**
 * Personal totals, so unlike /api/practice there is nothing to serve anonymously — the route
 * is behind .authenticated() in SecurityConfig.
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService statsService;
    private final InsightsService insightsService;

    public StatsController(StatsService statsService, InsightsService insightsService) {
        this.statsService = statsService;
        this.insightsService = insightsService;
    }

    @GetMapping("/me")
    public StatsResponse me(Authentication authentication) {
        return statsService.forUser(requireUserId(authentication));
    }

    /** Habits in the player's recent calls — see {@code PlayerInsights}. */
    @GetMapping("/me/insights")
    public InsightsResponse insights(Authentication authentication) {
        return insightsService.forUser(requireUserId(authentication));
    }

    /**
     * Carries a browser's stored tally into the account. Safe to call on every sign-in: the
     * first one takes, the rest just read back the current stats.
     */
    @PostMapping("/me/legacy")
    public StatsResponse importLegacy(@Valid @RequestBody LegacyStatsRequest request,
                                      Authentication authentication) {
        return statsService.importLegacy(requireUserId(authentication), request);
    }

    private Long requireUserId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
            throw new InvalidCredentialsException();
        }
        return userId;
    }
}

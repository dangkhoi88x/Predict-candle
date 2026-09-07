package com.example.candles.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.candles.config.CandlesProperties;
import com.example.candles.dto.request.GuessRequest;
import com.example.candles.dto.response.DailyRoundResponse;
import com.example.candles.dto.response.GuessResponse;
import com.example.candles.entity.GuessMode;
import com.example.candles.service.DailyRoundService;
import com.example.candles.service.RateLimiter;
import com.example.candles.service.RoundPlayService;

/**
 * The daily challenge: one chart a day, the same one for everybody, one attempt.
 *
 * Public like {@code /api/practice}. A signed-out visitor can play — nothing is recorded for
 * them, so their one attempt is only as strong as their browser, which is the same bargain
 * practice already makes and a better trade than putting a wallet in front of the thing the
 * whole feature exists to get people to try.
 */
@RestController
@RequestMapping("/api/daily")
public class DailyController {

    private final DailyRoundService dailyRoundService;
    private final RoundPlayService roundPlayService;
    private final CandlesProperties properties;
    private final RateLimiter rateLimiter;

    public DailyController(DailyRoundService dailyRoundService,
                           RoundPlayService roundPlayService,
                           CandlesProperties properties,
                           RateLimiter rateLimiter) {
        this.dailyRoundService = dailyRoundService;
        this.roundPlayService = roundPlayService;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/round")
    public DailyRoundResponse round(HttpServletRequest request) {
        rateLimiter.check("daily-round", properties.round().rateLimit().roundsPerMinute(), request);
        return dailyRoundService.round(currentUserId());
    }

    @PostMapping("/guess")
    public GuessResponse guess(@Valid @RequestBody GuessRequest body, HttpServletRequest request) {
        rateLimiter.check("daily-guess", properties.round().rateLimit().guessesPerMinute(), request);

        return roundPlayService.play(body, GuessMode.DAILY, dailyRoundService::checkIsToday);
    }

    /**
     * The filter authenticates as a bare {@code Long}; an unauthenticated request carries
     * Spring Security's anonymous token, whose principal is a String, so the match fails and
     * the caller is treated as signed out.
     */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Long userId) {
            return userId;
        }
        return null;
    }
}

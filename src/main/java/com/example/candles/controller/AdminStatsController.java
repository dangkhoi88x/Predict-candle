package com.example.candles.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.candles.dto.response.AdminStats;
import com.example.candles.service.AdminStatsService;
import com.example.candles.service.RateLimiter;

/**
 * The overview pane's charts. Read-only, so it leans on {@code hasRole("ADMIN")} in
 * SecurityConfig the same way {@code GET /api/admin/ops} does — the database re-read in
 * {@code AdminAccess} is what the writing endpoints need, and this one writes nothing.
 *
 * An unknown range is answered with the month view rather than a 400: the parameter names a
 * view of the same data, and there is no wrong answer to give.
 */
@RestController
@RequestMapping("/api/admin/stats")
public class AdminStatsController {

    /**
     * Only the cache-skipping form is limited. A plain read is answered from a minute-old copy
     * and costs nothing worth counting; `fresh=true` walks the guess table on purpose, and the
     * button that sends it is one people hold down.
     */
    private static final int FRESH_READS_PER_MINUTE = 20;

    private final AdminStatsService statsService;
    private final RateLimiter rateLimiter;

    public AdminStatsController(AdminStatsService statsService, RateLimiter rateLimiter) {
        this.statsService = statsService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * {@code fresh=true} is the overview's refresh button skipping the service's one-minute
     * cache. Without it that button updates the timestamp and the KPI figures while leaving
     * the charts on stale data — a refresh that visibly refreshes only half the page.
     */
    @GetMapping
    public AdminStats stats(@RequestParam(required = false) String range,
                            @RequestParam(defaultValue = "false") boolean fresh,
                            HttpServletRequest request) {
        if (fresh) rateLimiter.check("admin-stats-fresh", FRESH_READS_PER_MINUTE, request);
        return statsService.stats(range, fresh);
    }
}

package com.example.candles.api;

import com.example.candles.admin.AdminStats;
import com.example.candles.admin.AdminStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    private final AdminStatsService statsService;

    public AdminStatsController(AdminStatsService statsService) {
        this.statsService = statsService;
    }

    /**
     * {@code fresh=true} is the overview's refresh button skipping the service's one-minute
     * cache. Without it that button updates the timestamp and the KPI figures while leaving
     * the charts on stale data — a refresh that visibly refreshes only half the page.
     */
    @GetMapping
    public AdminStats stats(@RequestParam(required = false) String range,
                            @RequestParam(defaultValue = "false") boolean fresh) {
        return statsService.stats(range, fresh);
    }
}

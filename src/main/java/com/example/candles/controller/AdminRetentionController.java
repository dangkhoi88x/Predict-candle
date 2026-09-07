package com.example.candles.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.candles.dto.response.AdminRetention;
import com.example.candles.service.AdminRetentionService;
import com.example.candles.service.RateLimiter;

/**
 * Read-only, so it leans on {@code hasRole("ADMIN")} in SecurityConfig exactly like
 * {@code GET /api/admin/stats} — the database re-read in {@code AdminAccess} is for endpoints
 * that write, and this one does not.
 *
 * An out-of-range {@code days} is clamped rather than refused: it names how far back to look,
 * and there is no wrong answer to give.
 */
@RestController
@RequestMapping("/api/admin/retention")
public class AdminRetentionController {

    /** Same reasoning as the overview's: only the read that skips the cache is worth limiting. */
    private static final int FRESH_READS_PER_MINUTE = 20;

    private final AdminRetentionService retentionService;
    private final RateLimiter rateLimiter;

    public AdminRetentionController(AdminRetentionService retentionService, RateLimiter rateLimiter) {
        this.retentionService = retentionService;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public AdminRetention retention(@RequestParam(required = false) Integer days,
                                    @RequestParam(defaultValue = "false") boolean fresh,
                                    HttpServletRequest request) {
        if (fresh) rateLimiter.check("admin-retention-fresh", FRESH_READS_PER_MINUTE, request);
        return retentionService.retention(days, fresh);
    }
}

package com.example.candles.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.candles.dto.OpsSnapshot;
import com.example.candles.security.AdminAccess;
import com.example.candles.service.OpsService;
import com.example.candles.service.RateLimiter;

/** Health and settings, plus the one button: sync an asset's candles now. */
@RestController
@RequestMapping("/api/admin/ops")
public class AdminOpsController {

    /**
     * Ten a minute. Sync is a button someone presses when a chart looks behind, and every press
     * is a request to Binance — a loop here spends someone else's rate limit, and being banned
     * by the data provider breaks the game itself, not just the admin page.
     */
    private static final int SYNCS_PER_MINUTE = 10;

    private final OpsService opsService;
    private final AdminAccess adminAccess;
    private final RateLimiter rateLimiter;

    public AdminOpsController(OpsService opsService, AdminAccess adminAccess,
                              RateLimiter rateLimiter) {
        this.opsService = opsService;
        this.adminAccess = adminAccess;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public OpsSnapshot snapshot() {
        return opsService.snapshot();
    }

    /**
     * Reaches out to Binance and writes candles, so it re-checks the role against the database
     * like every other write here.
     */
    @PostMapping("/sync/{symbol}")
    public OpsSnapshot.AssetHealth sync(@PathVariable String symbol, HttpServletRequest request) {
        adminAccess.requireAdmin();
        rateLimiter.check("ops-sync", SYNCS_PER_MINUTE, request);
        return opsService.syncNow(symbol);
    }
}

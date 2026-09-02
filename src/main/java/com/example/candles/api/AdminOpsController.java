package com.example.candles.api;

import com.example.candles.auth.AdminAccess;
import com.example.candles.ops.OpsService;
import com.example.candles.ops.OpsSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Health and settings, plus the one button: sync an asset's candles now. */
@RestController
@RequestMapping("/api/admin/ops")
public class AdminOpsController {

    private final OpsService opsService;
    private final AdminAccess adminAccess;

    public AdminOpsController(OpsService opsService, AdminAccess adminAccess) {
        this.opsService = opsService;
        this.adminAccess = adminAccess;
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
    public OpsSnapshot.AssetHealth sync(@PathVariable String symbol) {
        adminAccess.requireAdmin();
        return opsService.syncNow(symbol);
    }
}

package com.example.candles.controller;

import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

import com.example.candles.security.AdminAccess;
import com.example.candles.service.AdminAssetService;

@RestController
@RequestMapping("/api/admin/assets")
public class AdminAssetController {

    private final AdminAssetService assetService;
    private final AdminAccess adminAccess;

    public AdminAssetController(AdminAssetService assetService, AdminAccess adminAccess) {
        this.assetService = assetService;
        this.adminAccess = adminAccess;
    }

    @GetMapping
    public List<AdminAssetService.AssetSummary> list() {
        return assetService.assets();
    }

    @PostMapping
    public AdminAssetService.AssetSummary create(@RequestBody CreateAssetRequest request) {
        adminAccess.requireAdmin();
        return assetService.create(request.symbol(), request.name());
    }

    @PostMapping("/{id}/enabled")
    public AdminAssetService.AssetSummary setEnabled(@PathVariable Long id,
                                                      @RequestBody EnabledRequest request) {
        adminAccess.requireAdmin();
        return assetService.setEnabled(id, request.enabled());
    }

    @PostMapping("/{id}/move")
    public List<AdminAssetService.AssetSummary> move(@PathVariable Long id,
                                                     @RequestBody MoveRequest request) {
        adminAccess.requireAdmin();
        return assetService.move(id, "up".equalsIgnoreCase(request.direction()));
    }

    /** Kicks off the history fetch for a pair that has none. Can take a while on first call. */
    @PostMapping("/{id}/backfill")
    public AdminAssetService.AssetSummary backfill(@PathVariable Long id) {
        adminAccess.requireAdmin();
        return assetService.backfill(id);
    }

    public record CreateAssetRequest(@NotBlank String symbol, String name) {
    }

    public record EnabledRequest(boolean enabled) {
    }

    public record MoveRequest(String direction) {
    }
}

package com.example.candles.controller;

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

import com.example.candles.dto.PlayerSummary;
import com.example.candles.security.AdminAccess;
import com.example.candles.service.AdminPlayerService;

/**
 * Accounts. Read, rename, delete — no way to grant a role (configuration owns that) and no way
 * to adjust anyone's totals.
 */
@RestController
@RequestMapping("/api/admin/players")
public class AdminPlayerController {

    private final AdminPlayerService playerService;
    private final AdminAccess adminAccess;

    public AdminPlayerController(AdminPlayerService playerService, AdminAccess adminAccess) {
        this.playerService = playerService;
        this.adminAccess = adminAccess;
    }

    @GetMapping
    public List<PlayerSummary> list() {
        return playerService.players();
    }

    @PutMapping("/{id}/name")
    public PlayerSummary rename(@PathVariable Long id, @RequestBody RenameRequest request) {
        adminAccess.requireAdmin();
        return playerService.rename(id, request.displayName());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        adminAccess.requireAdmin();
        playerService.delete(id);
    }

    public record RenameRequest(@NotBlank String displayName) {
    }
}

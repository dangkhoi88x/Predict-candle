package com.example.candles.controller;

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.example.candles.dto.response.AdminPlayerDetail;
import com.example.candles.dto.response.AdminPlayerPage;
import com.example.candles.dto.response.PlayerSummary;
import com.example.candles.security.AdminAccess;
import com.example.candles.service.AdminPlayerService;

/**
 * Accounts: a page of them, one of them in detail, rename and delete. No way to grant a role
 * (configuration owns that) and no way to adjust anyone's totals — the detail view keeps that
 * bargain, reading the rows the game scores on and offering nothing to change them with.
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

    /**
     * One page, filtered by {@code query} and ordered by {@code sort} ("active" or "recent").
     * Every parameter is optional: no arguments is the first page of the busiest accounts,
     * which is what this endpoint used to be the whole of.
     */
    @GetMapping
    public AdminPlayerPage list(@RequestParam(required = false) String query,
                                 @RequestParam(required = false) String sort,
                                 @RequestParam(required = false) Integer page,
                                 @RequestParam(required = false) Integer size) {
        return playerService.players(query, sort, page, size);
    }

    /** Everything recorded against one account — read-only, like the list it opens from. */
    @GetMapping("/{id}")
    public AdminPlayerDetail detail(@PathVariable Long id) {
        return playerService.detail(id);
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

package com.example.candles.controller;

import com.example.candles.dto.response.AdminLiveRoundDetail;
import com.example.candles.dto.response.AdminLiveRounds;
import com.example.candles.security.AdminAccess;
import com.example.candles.service.AdminLiveRoundService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading and voiding live rounds.
 *
 * {@code /api/live/**} answers the same questions for players and deliberately answers them
 * without names or addresses; these are the admin's copies, behind the role, and they carry
 * both. The delete is the one thing here with no counterpart anywhere: a live result is
 * recomputed from the exchange's candle on every read, so a round that settled on a bad price
 * cannot be corrected — only cleared.
 */
@RestController
@RequestMapping("/api/admin/live")
public class AdminLiveRoundController {

    private final AdminLiveRoundService liveRounds;
    private final AdminAccess adminAccess;

    public AdminLiveRoundController(AdminLiveRoundService liveRounds, AdminAccess adminAccess) {
        this.liveRounds = liveRounds;
        this.adminAccess = adminAccess;
    }

    @GetMapping("/rounds")
    public AdminLiveRounds rounds(@RequestParam String asset,
                                   @RequestParam(required = false) Integer limit) {
        return liveRounds.rounds(asset, limit);
    }

    @GetMapping("/rounds/{roundNumber}")
    public AdminLiveRoundDetail detail(@PathVariable long roundNumber, @RequestParam String asset) {
        return liveRounds.detail(asset, roundNumber);
    }

    /** Returns how many calls were removed, which is zero on a round nobody played. */
    @DeleteMapping("/rounds/{roundNumber}")
    public VoidResult voidRound(@PathVariable long roundNumber, @RequestParam String asset) {
        Long adminId = adminAccess.requireAdmin().getId();
        return new VoidResult(roundNumber, liveRounds.voidRound(asset, roundNumber, adminId));
    }

    public record VoidResult(long round, int removed) {
    }
}

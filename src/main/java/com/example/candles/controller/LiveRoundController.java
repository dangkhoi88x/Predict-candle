package com.example.candles.controller;

import com.example.candles.dto.request.LivePredictRequest;
import com.example.candles.dto.response.LiveRoundHistoryResponse;
import com.example.candles.dto.response.LiveRoundResponse;
import com.example.candles.entity.Direction;
import com.example.candles.service.LiveRoundService;
import com.example.candles.service.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The live game: one shared call on the candle the exchange is forming right now, as opposed to
 * {@code /api/practice}'s random historical chart. Reading the round is public; calling a
 * direction needs a wallet, enforced in {@code SecurityConfig} rather than here.
 */
@RestController
@RequestMapping("/api/live")
public class LiveRoundController {

    private final LiveRoundService liveRoundService;
    private final RateLimiter rateLimiter;

    public LiveRoundController(LiveRoundService liveRoundService, RateLimiter rateLimiter) {
        this.liveRoundService = liveRoundService;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/round")
    public LiveRoundResponse round(@RequestParam String asset, Authentication authentication,
                                    HttpServletRequest request) {
        rateLimiter.check("live-round", 120, request);
        return liveRoundService.snapshot(asset, callerId(authentication));
    }

    @PostMapping("/predict")
    public LiveRoundResponse predict(@Valid @RequestBody LivePredictRequest body,
                                      Authentication authentication, HttpServletRequest request) {
        rateLimiter.check("live-predict", 20, request);
        // SecurityConfig requires authentication on this route, so the principal is always set.
        Long userId = (Long) authentication.getPrincipal();
        return liveRoundService.predict(body.asset(), Direction.valueOf(body.direction()), userId);
    }

    @GetMapping("/history")
    public LiveRoundHistoryResponse history(@RequestParam String asset, HttpServletRequest request) {
        rateLimiter.check("live-history", 60, request);
        return liveRoundService.history(asset);
    }

    private Long callerId(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof Long id ? id : null;
    }
}

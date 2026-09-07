package com.example.candles.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.candles.config.CandlesProperties;
import com.example.candles.dto.request.DemoTradeRequest;
import com.example.candles.dto.response.DemoPortfolioResponse;
import com.example.candles.service.DemoTradingService;
import com.example.candles.service.RateLimiter;

/**
 * Paper trading. Every route needs an account — a portfolio that belongs to nobody cannot be
 * held to a balance, so unlike the rest of the game there is no anonymous path here.
 * SecurityConfig enforces that for the whole prefix.
 */
@RestController
@RequestMapping("/api/demo")
public class DemoTradingController {

    private final DemoTradingService trading;
    private final CandlesProperties properties;
    private final RateLimiter rateLimiter;

    public DemoTradingController(DemoTradingService trading,
                                 CandlesProperties properties,
                                 RateLimiter rateLimiter) {
        this.trading = trading;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/portfolio")
    public DemoPortfolioResponse portfolio(HttpServletRequest request) {
        rateLimiter.check("demo-portfolio", properties.round().rateLimit().roundsPerMinute(), request);
        return trading.portfolio(currentUserId());
    }

    @PostMapping("/trade")
    public DemoPortfolioResponse trade(@Valid @RequestBody DemoTradeRequest body,
                                       HttpServletRequest request) {
        rateLimiter.check("demo-trade", properties.round().rateLimit().guessesPerMinute(), request);
        return trading.trade(currentUserId(), body.asset(), body.side(),
                body.amountUsd(), body.quantity());
    }

    @PostMapping("/reset")
    public DemoPortfolioResponse reset(HttpServletRequest request) {
        rateLimiter.check("demo-reset", properties.round().rateLimit().guessesPerMinute(), request);
        return trading.reset(currentUserId());
    }

    /** Never null here: the whole prefix sits behind {@code .authenticated()}. */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof Long id ? id : null;
    }
}

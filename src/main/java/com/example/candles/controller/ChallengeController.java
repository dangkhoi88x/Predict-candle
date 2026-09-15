package com.example.candles.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.candles.config.CandlesProperties;
import com.example.candles.dto.request.ChallengeCreateRequest;
import com.example.candles.dto.request.GuessRequest;
import com.example.candles.dto.response.ChallengeCreatedResponse;
import com.example.candles.dto.response.ChallengeRoundResponse;
import com.example.candles.dto.response.GuessResponse;
import com.example.candles.entity.GuessMode;
import com.example.candles.service.ChallengeService;
import com.example.candles.service.RateLimiter;
import com.example.candles.service.RoundPlayService;

/**
 * Challenge links. Public end to end, like practice: a friend who was sent a link should be able to
 * play it before deciding to sign in. Signing in is what records the attempt and lists them.
 */
@RestController
@RequestMapping("/api/challenges")
public class ChallengeController {

    private final ChallengeService challengeService;
    private final RoundPlayService roundPlayService;
    private final RateLimiter rateLimiter;
    private final CandlesProperties properties;

    public ChallengeController(ChallengeService challengeService, RoundPlayService roundPlayService,
                               RateLimiter rateLimiter, CandlesProperties properties) {
        this.challengeService = challengeService;
        this.roundPlayService = roundPlayService;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @PostMapping
    public ChallengeCreatedResponse create(@Valid @RequestBody ChallengeCreateRequest body, HttpServletRequest request) {
        // Each creation writes a row; nothing a person does needs more than a few a minute.
        rateLimiter.check("challenge-create", 20, request);
        return challengeService.create(body.challengeToken(), currentUserId());
    }

    @GetMapping("/{id}")
    public ChallengeRoundResponse round(@PathVariable String id, HttpServletRequest request) {
        rateLimiter.check("challenge-round", properties.round().rateLimit().roundsPerMinute(), request);
        return challengeService.round(id, currentUserId());
    }

    @PostMapping("/{id}/guess")
    public GuessResponse guess(@PathVariable String id, @Valid @RequestBody GuessRequest body,
                               HttpServletRequest request) {
        rateLimiter.check("challenge-guess", properties.round().rateLimit().guessesPerMinute(), request);
        Long callerId = currentUserId();
        return roundPlayService.play(body, GuessMode.CHALLENGE,
                token -> challengeService.checkToken(token, id, callerId),
                (asset, timeframe, startIndex, guessNumber, guessed, actual, mode) ->
                        challengeService.record(id, callerId, guessNumber, guessed, actual));
    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Long userId) {
            return userId;
        }
        return null;
    }
}

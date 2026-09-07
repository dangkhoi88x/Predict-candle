package com.example.candles.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.RoundSelection;
import com.example.candles.domain.RoundToken;
import com.example.candles.dto.request.GuessRequest;
import com.example.candles.dto.response.CandleDto;
import com.example.candles.dto.response.GuessResponse;
import com.example.candles.dto.response.RoundResponse;
import com.example.candles.entity.GuessMode;
import com.example.candles.service.RateLimiter;
import com.example.candles.service.RoundPlayService;
import com.example.candles.service.RoundSelectionService;
import com.example.candles.service.RoundTimingPolicy;
import com.example.candles.service.RoundTokenService;

@RestController
@RequestMapping("/api/practice")
public class PracticeController {

    private final RoundSelectionService roundSelectionService;
    private final RoundTokenService roundTokenService;
    private final RoundPlayService roundPlayService;
    private final CandlesProperties properties;
    private final RoundTimingPolicy timingPolicy;
    private final RateLimiter rateLimiter;

    public PracticeController(RoundSelectionService roundSelectionService,
                               RoundTokenService roundTokenService,
                               RoundPlayService roundPlayService,
                               CandlesProperties properties,
                               RoundTimingPolicy timingPolicy,
                               RateLimiter rateLimiter) {
        this.roundSelectionService = roundSelectionService;
        this.roundTokenService = roundTokenService;
        this.roundPlayService = roundPlayService;
        this.properties = properties;
        this.timingPolicy = timingPolicy;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/round")
    public RoundResponse getRound(@RequestParam String asset, HttpServletRequest request) {
        rateLimiter.check("round", properties.round().rateLimit().roundsPerMinute(), request);

        RoundSelection selection = roundSelectionService.selectRound(asset);

        String token = roundTokenService.generate(new RoundToken(
                selection.asset().getId(),
                selection.timeframe(),
                selection.startIndex(),
                1,
                GuessMode.PRACTICE,
                0
        ));

        return new RoundResponse(
                selection.asset().getSymbol(),
                selection.timeframe(),
                selection.visibleCandles().stream().map(CandleDto::from).toList(),
                properties.round().guessesPerChart(),
                timingPolicy.guessSeconds(),
                token
        );
    }

    /**
     * Practice asks nothing of a token beyond a valid signature and the right mode — any chart
     * is playable, any number of times. The daily challenge is the one with more to check.
     */
    @PostMapping("/guess")
    public GuessResponse guess(@Valid @RequestBody GuessRequest body, HttpServletRequest request) {
        rateLimiter.check("guess", properties.round().rateLimit().guessesPerMinute(), request);

        return roundPlayService.play(body, GuessMode.PRACTICE, token -> { });
    }
}

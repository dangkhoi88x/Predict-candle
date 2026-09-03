package com.example.candles.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.RoundSelection;
import com.example.candles.domain.RoundToken;
import com.example.candles.dto.CandleDto;
import com.example.candles.dto.DatedCandleDto;
import com.example.candles.dto.GuessRequest;
import com.example.candles.dto.GuessResponse;
import com.example.candles.dto.RoundResponse;
import com.example.candles.entity.Asset;
import com.example.candles.entity.Candle;
import com.example.candles.entity.Direction;
import com.example.candles.repository.AssetRepository;
import com.example.candles.service.GuessResultService;
import com.example.candles.service.RateLimiter;
import com.example.candles.service.RoundPatternScanner;
import com.example.candles.service.RoundSelectionService;
import com.example.candles.service.RoundTimingPolicy;
import com.example.candles.service.RoundTokenService;

@RestController
@RequestMapping("/api/practice")
public class PracticeController {

    private final RoundSelectionService roundSelectionService;
    private final RoundTokenService roundTokenService;
    private final AssetRepository assetRepository;
    private final CandlesProperties properties;
    private final GuessResultService guessResultService;
    private final RoundPatternScanner patternScanner;
    private final RoundTimingPolicy timingPolicy;
    private final RateLimiter rateLimiter;

    public PracticeController(RoundSelectionService roundSelectionService,
                               RoundTokenService roundTokenService,
                               AssetRepository assetRepository,
                               CandlesProperties properties,
                               GuessResultService guessResultService,
                               RoundPatternScanner patternScanner,
                               RoundTimingPolicy timingPolicy,
                               RateLimiter rateLimiter) {
        this.roundSelectionService = roundSelectionService;
        this.roundTokenService = roundTokenService;
        this.assetRepository = assetRepository;
        this.properties = properties;
        this.guessResultService = guessResultService;
        this.patternScanner = patternScanner;
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
                1
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

    @PostMapping("/guess")
    public GuessResponse guess(@Valid @RequestBody GuessRequest body, HttpServletRequest request) {
        rateLimiter.check("guess", properties.round().rateLimit().guessesPerMinute(), request);

        RoundTokenService.Verified verified = roundTokenService.verify(body.roundToken());
        timingPolicy.check(verified.issuedAt(), body.answered());

        RoundToken token = verified.round();
        // Null all the way through rather than a sentinel direction: the countdown expiring is
        // an absence of an answer, and every place downstream should have to say what it does
        // with that instead of quietly treating it as a guess that happened to be wrong.
        Direction guess = body.answered() ? Direction.valueOf(body.direction().toUpperCase()) : null;

        Asset asset = assetRepository.findById(token.assetId())
                .orElseThrow(() -> new IllegalStateException("Asset in round token no longer exists"));
        Candle actual = roundSelectionService.answerCandleAt(
                asset, token.timeframe(), token.startIndex(), token.guessNumber());
        Direction actualDirection = actual.getClose().compareTo(actual.getOpen()) >= 0 ? Direction.LONG : Direction.SHORT;

        // No-op for anonymous play, which stays supported.
        guessResultService.record(asset, token.timeframe(), token.startIndex(),
                token.guessNumber(), guess, actualDirection);

        int totalGuesses = properties.round().guessesPerChart();
        boolean sessionComplete = token.guessNumber() >= totalGuesses;
        String nextToken = sessionComplete ? null : roundTokenService.generate(new RoundToken(
                token.assetId(), token.timeframe(), token.startIndex(), token.guessNumber() + 1));

        List<Candle> revealed = sessionComplete
                ? roundSelectionService.revealCandlesAfter(asset, token.timeframe(), token.startIndex(), totalGuesses)
                : List.of();

        /*
         * Naming the chart mid-session would either say nothing the player cannot already see
         * or hand them a date to go and look up. At the end it is the payoff: the run of
         * candles they just read turns back into a week they might remember.
         */
        GuessResponse.RoundIdentity identity = sessionComplete
                ? new GuessResponse.RoundIdentity(
                        asset.getSymbol(),
                        token.timeframe(),
                        roundSelectionService.candleAt(asset, token.timeframe(), token.startIndex()).getOpenTime(),
                        (revealed.isEmpty() ? actual : revealed.getLast()).getOpenTime())
                : null;

        GuessResponse.RoundContext context = null;
        if (sessionComplete) {
            RoundSelectionService.ContextWindow window =
                    roundSelectionService.contextWindow(asset, token.timeframe(), token.startIndex());
            int playedFrom = window.leading();
            int guessFrom = playedFrom + properties.round().visibleCandles();
            // Scanned over the whole context but reported only where the player was looking:
            // a pattern that completes in the trailing padding was never part of the puzzle.
            List<RoundPatternScanner.PatternHit> hits =
                    patternScanner.scan(window.candles(), playedFrom, guessFrom + totalGuesses);

            context = new GuessResponse.RoundContext(
                    window.candles().stream().map(DatedCandleDto::from).toList(),
                    playedFrom,
                    guessFrom,
                    totalGuesses,
                    hits.stream()
                            .map(h -> new GuessResponse.PatternMark(h.patternId(), h.startIndex(), h.length()))
                            .toList());
        }

        return new GuessResponse(
                guess == actualDirection,
                actualDirection.name(),
                CandleDto.from(actual),
                token.guessNumber(),
                totalGuesses,
                sessionComplete,
                nextToken,
                revealed.stream().map(CandleDto::from).toList(),
                identity,
                context
        );
    }
}

package com.example.candles.api;

import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.Asset;
import com.example.candles.domain.Candle;
import com.example.candles.domain.Direction;
import com.example.candles.repository.AssetRepository;
import com.example.candles.round.RoundSelection;
import com.example.candles.round.RoundSelectionService;
import com.example.candles.round.RoundToken;
import com.example.candles.round.RoundTokenService;
import jakarta.validation.Valid;
import java.util.Collections;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/practice")
public class PracticeController {

    private final RoundSelectionService roundSelectionService;
    private final RoundTokenService roundTokenService;
    private final AssetRepository assetRepository;
    private final CandlesProperties properties;

    public PracticeController(RoundSelectionService roundSelectionService,
                               RoundTokenService roundTokenService,
                               AssetRepository assetRepository,
                               CandlesProperties properties) {
        this.roundSelectionService = roundSelectionService;
        this.roundTokenService = roundTokenService;
        this.assetRepository = assetRepository;
        this.properties = properties;
    }

    @GetMapping("/round")
    public RoundResponse getRound(@RequestParam String asset) {
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
                token
        );
    }

    @PostMapping("/guess")
    public GuessResponse guess(@Valid @RequestBody GuessRequest request) {
        RoundToken token = roundTokenService.verify(request.roundToken());
        Direction guess = Direction.valueOf(request.direction().toUpperCase());

        Asset asset = assetRepository.findById(token.assetId())
                .orElseThrow(() -> new IllegalStateException("Asset in round token no longer exists"));
        Candle actual = roundSelectionService.answerCandleAt(
                asset, token.timeframe(), token.startIndex(), token.guessNumber());
        Direction actualDirection = actual.getClose().compareTo(actual.getOpen()) >= 0 ? Direction.LONG : Direction.SHORT;

        int totalGuesses = properties.round().guessesPerChart();
        boolean sessionComplete = token.guessNumber() >= totalGuesses;
        String nextToken = sessionComplete ? null : roundTokenService.generate(new RoundToken(
                token.assetId(), token.timeframe(), token.startIndex(), token.guessNumber() + 1));

        List<CandleDto> revealCandles = sessionComplete
                ? roundSelectionService.revealCandlesAfter(asset, token.timeframe(), token.startIndex(), totalGuesses)
                        .stream().map(CandleDto::from).toList()
                : Collections.emptyList();

        return new GuessResponse(
                guess == actualDirection,
                actualDirection.name(),
                CandleDto.from(actual),
                token.guessNumber(),
                totalGuesses,
                sessionComplete,
                nextToken,
                revealCandles
        );
    }
}

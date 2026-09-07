package com.example.candles.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.RoundToken;
import com.example.candles.dto.request.GuessRequest;
import com.example.candles.dto.response.CandleDto;
import com.example.candles.dto.response.DatedCandleDto;
import com.example.candles.dto.response.GuessResponse;
import com.example.candles.dto.response.RoundHints;
import com.example.candles.entity.Asset;
import com.example.candles.entity.Candle;
import com.example.candles.entity.Direction;
import com.example.candles.entity.GuessMode;
import com.example.candles.exception.InvalidRoundTokenException;
import com.example.candles.repository.AssetRepository;

/**
 * Answering one guess of a multi-guess chart — the part practice and the daily challenge do
 * identically.
 *
 * They differ in where the chart came from and what may be played, not in what happens when a
 * guess arrives: verify the token, check it was not answered impossibly fast, work out which
 * way the candle actually went, record it, and reveal the ending once the last guess is in.
 * Keeping one copy of that is what stops the two games drifting into scoring the same guess
 * differently.
 *
 * {@code mode} travels into the recorded row and is checked against the token, so a token
 * minted for one game cannot be spent in the other.
 */
@Service
public class RoundPlayService {

    private final RoundSelectionService roundSelectionService;
    private final RoundTokenService roundTokenService;
    private final AssetRepository assetRepository;
    private final CandlesProperties properties;
    private final GuessResultService guessResultService;
    private final RoundPatternScanner patternScanner;
    private final RoundTimingPolicy timingPolicy;
    private final RoundHintService hintService;

    public RoundPlayService(RoundSelectionService roundSelectionService,
                            RoundTokenService roundTokenService,
                            AssetRepository assetRepository,
                            CandlesProperties properties,
                            GuessResultService guessResultService,
                            RoundPatternScanner patternScanner,
                            RoundTimingPolicy timingPolicy,
                            RoundHintService hintService) {
        this.roundSelectionService = roundSelectionService;
        this.roundTokenService = roundTokenService;
        this.assetRepository = assetRepository;
        this.properties = properties;
        this.guessResultService = guessResultService;
        this.patternScanner = patternScanner;
        this.timingPolicy = timingPolicy;
        this.hintService = hintService;
    }

    /**
     * @param extraCheck run once the token verifies, for whatever the calling game needs to be
     *                   true beyond a valid signature — the daily challenge uses it to refuse a
     *                   token for a day that has since rolled over.
     */
    public GuessResponse play(GuessRequest body, GuessMode mode, Consumer<RoundToken> extraCheck) {
        RoundTokenService.Verified verified = roundTokenService.verify(body.roundToken());
        timingPolicy.check(verified.issuedAt(), body.answered());

        RoundToken token = verified.round();
        if (token.mode() != mode) {
            throw new InvalidRoundTokenException("Lượt chơi này không thuộc chế độ đang chơi.");
        }
        extraCheck.accept(token);

        // Null all the way through rather than a sentinel direction: the countdown expiring is
        // an absence of an answer, and every place downstream should have to say what it does
        // with that instead of quietly treating it as a guess that happened to be wrong.
        Direction guess = body.answered() ? Direction.valueOf(body.direction().toUpperCase()) : null;

        Asset asset = assetRepository.findById(token.assetId())
                .orElseThrow(() -> new IllegalStateException("Asset in round token no longer exists"));
        Candle actual = roundSelectionService.answerCandleAt(
                asset, token.timeframe(), token.startIndex(), token.guessNumber());
        Direction actualDirection = actual.getClose().compareTo(actual.getOpen()) >= 0
                ? Direction.LONG : Direction.SHORT;

        // No-op for anonymous play, which stays supported.
        guessResultService.record(asset, token.timeframe(), token.startIndex(),
                token.guessNumber(), guess, actualDirection, mode);

        int totalGuesses = properties.round().guessesPerChart();
        boolean sessionComplete = token.guessNumber() >= totalGuesses;

        // A guess the countdown ate counts as a miss: the player did not read the chart, which
        // is the thing the hints are there to help with.
        int misses = token.misses() + (guess == actualDirection ? 0 : 1);
        String nextToken = sessionComplete ? null : roundTokenService.generate(new RoundToken(
                token.assetId(), token.timeframe(), token.startIndex(), token.guessNumber() + 1,
                mode, misses));
        RoundHints nextHints = sessionComplete ? RoundHints.NONE : hintService.hintsFor(
                token.assetId(), token.timeframe(), token.startIndex(),
                token.guessNumber() + 1, misses);

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
                context,
                nextHints
        );
    }
}

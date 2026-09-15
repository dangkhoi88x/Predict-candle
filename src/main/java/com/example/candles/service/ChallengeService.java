package com.example.candles.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;

import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.ChallengeResult;
import com.example.candles.domain.RoundToken;
import com.example.candles.dto.response.CandleDto;
import com.example.candles.dto.response.ChallengeCreatedResponse;
import com.example.candles.dto.response.ChallengeRoundResponse;
import com.example.candles.dto.response.DailyRoundResponse;
import com.example.candles.dto.response.RoundHints;
import com.example.candles.entity.Asset;
import com.example.candles.entity.Challenge;
import com.example.candles.entity.ChallengeGuess;
import com.example.candles.entity.Direction;
import com.example.candles.entity.GuessMode;
import com.example.candles.entity.User;
import com.example.candles.exception.InvalidRoundTokenException;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.CandleRepository;
import com.example.candles.repository.ChallengeGuessRepository;
import com.example.candles.repository.ChallengeRepository;
import com.example.candles.repository.UserRepository;

/**
 * Challenge links: a finished practice chart a friend can play, and how everyone did on it.
 *
 * The shape is the daily's, pointed at a chart somebody chose instead of the date — the same token
 * chain, the same resume-from-rows for a signed-in player, the same one attempt enforced by a
 * unique constraint. Two things differ, and both are about the creator having seen the answers:
 * the guesses go to {@code challenge_guesses} rather than {@code guess_results}, so no score or
 * ranking can be farmed off a link, and the creator cannot play their own challenge at all.
 */
@Service
public class ChallengeService {

    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"; // no l/1, o/0
    private static final int ID_LENGTH = 8;
    private static final int FINISHERS_SHOWN = 50;
    static final String ANONYMOUS_CREATOR = "Một người chơi";

    private final SecureRandom random = new SecureRandom();

    private final ChallengeRepository challenges;
    private final ChallengeGuessRepository challengeGuesses;
    private final AssetRepository assets;
    private final UserRepository users;
    private final CandleRepository candles;
    private final RoundSelectionService rounds;
    private final RoundTokenService tokens;
    private final RoundHintService hintService;
    private final RoundTimingPolicy timingPolicy;
    private final CandlesProperties properties;

    public ChallengeService(ChallengeRepository challenges, ChallengeGuessRepository challengeGuesses,
                            AssetRepository assets, UserRepository users, CandleRepository candles,
                            RoundSelectionService rounds, RoundTokenService tokens,
                            RoundHintService hintService, RoundTimingPolicy timingPolicy,
                            CandlesProperties properties) {
        this.challenges = challenges;
        this.challengeGuesses = challengeGuesses;
        this.assets = assets;
        this.users = users;
        this.candles = candles;
        this.rounds = rounds;
        this.tokens = tokens;
        this.hintService = hintService;
        this.timingPolicy = timingPolicy;
        this.properties = properties;
    }

    /** Turns a signed practice result into a link. A signed-in creator asking twice gets the same one. */
    @Transactional
    public ChallengeCreatedResponse create(String challengeToken, Long callerId) {
        ChallengeResult result = tokens.verifyChallengeResult(challengeToken);
        Asset asset = assets.findById(result.assetId())
                .orElseThrow(() -> new IllegalArgumentException("Cặp giao dịch của biểu đồ này không còn nữa."));
        User creator = callerId == null ? null : users.findById(callerId).orElse(null);

        if (creator != null) {
            var existing = challenges.findFirstByCreatorIdAndAssetIdAndTimeframeAndStartIndex(
                    creator.getId(), asset.getId(), result.timeframe(), result.startIndex());
            if (existing.isPresent()) return created(existing.get());
        }

        String name = creator == null || creator.getDisplayName() == null ? ANONYMOUS_CREATOR : creator.getDisplayName();
        if (name.length() > 64) name = name.substring(0, 64);
        Challenge challenge = new Challenge(freshId(), asset, result.timeframe(), result.startIndex(),
                creator, name, result.correct(), result.total());
        return created(challenges.save(challenge));
    }

    @Transactional(readOnly = true)
    public ChallengeRoundResponse round(String id, Long callerId) {
        Challenge challenge = find(id);
        Asset asset = challenge.getAsset();
        int totalGuesses = properties.round().guessesPerChart();
        boolean mine = isCreator(challenge, callerId);

        List<ChallengeGuess> played = callerId == null || mine ? List.of()
                : challengeGuesses.findByChallengeIdAndUserIdOrderByGuessNumber(id, callerId);
        int guessesMade = played.size();
        boolean completed = mine || guessesMade >= totalGuesses;
        int misses = (int) played.stream().filter(g -> !g.isCorrect()).count();

        String token = completed ? null : tokens.generate(new RoundToken(asset.getId(),
                challenge.getTimeframe(), challenge.getStartIndex(), guessesMade + 1, GuessMode.CHALLENGE, misses));
        RoundHints hints = completed ? RoundHints.NONE : hintService.hintsFor(asset.getId(),
                challenge.getTimeframe(), challenge.getStartIndex(), guessesMade + 1, misses);
        List<CandleDto> resolved = completed
                ? rounds.answerCandles(asset, challenge.getTimeframe(), challenge.getStartIndex(), totalGuesses)
                        .stream().map(CandleDto::from).toList()
                : List.of();

        return new ChallengeRoundResponse(
                challenge.getId(),
                asset.getSymbol(),
                challenge.getTimeframe(),
                candles.findWindow(asset.getId(), challenge.getTimeframe(), challenge.getStartIndex(),
                        properties.round().visibleCandles()).stream().map(CandleDto::from).toList(),
                totalGuesses,
                timingPolicy.guessSeconds(),
                token,
                guessesMade,
                completed,
                played.stream().map(g -> new DailyRoundResponse.Answer(g.getGuessNumber(),
                        g.getGuessedDirection() == null ? null : g.getGuessedDirection().name(),
                        g.getActualDirection().name(), g.isCorrect())).toList(),
                resolved,
                hints,
                creatorName(challenge),
                challenge.getCreatorCorrect(),
                mine,
                finishers(challenge, callerId, totalGuesses));
    }

    /**
     * The token has to be for this challenge's chart and for a challenge — the path names the
     * challenge and the token only a chart, so this is what makes the two agree. Without it a
     * practice token could be spent here, or one challenge's token on another.
     */
    @Transactional(readOnly = true)
    public void checkToken(RoundToken token, String id, Long callerId) {
        if (token.mode() != GuessMode.CHALLENGE) {
            throw new InvalidRoundTokenException("Không phải lượt chơi thách đấu.");
        }
        Challenge challenge = find(id);
        if (!challenge.getAsset().getId().equals(token.assetId())
                || !challenge.getTimeframe().equals(token.timeframe())
                || challenge.getStartIndex() != token.startIndex()) {
            throw new InvalidRoundTokenException("Lượt chơi không khớp với thách đấu này.");
        }
        if (isCreator(challenge, callerId)) {
            throw new IllegalArgumentException("Bạn tạo thử thách này nên không chơi được nó.");
        }
    }

    /** Signed-in only, and best-effort like GuessResultService: recording never fails a guess. */
    @Transactional
    public void record(String id, Long callerId, int guessNumber, Direction guessed, Direction actual) {
        if (callerId == null) return;
        if (challengeGuesses.existsByChallengeIdAndUserIdAndGuessNumber(id, callerId, guessNumber)) return;
        User user = users.findById(callerId).orElse(null);
        if (user == null) return;
        try {
            challengeGuesses.save(new ChallengeGuess(find(id), user, guessNumber, guessed, actual));
        } catch (DataIntegrityViolationException e) {
            // Two requests for the same guess raced; the constraint settled it.
        }
    }

    private List<ChallengeRoundResponse.Finisher> finishers(Challenge challenge, Long callerId, int total) {
        return challengeGuesses.finishers(challenge.getId(), total, PageRequest.of(0, FINISHERS_SHOWN)).stream()
                .map(row -> {
                    User user = (User) row[0];
                    return new ChallengeRoundResponse.Finisher(
                            user.getDisplayName() == null ? user.getShortWalletAddress() : user.getDisplayName(),
                            ((Number) row[1]).intValue(), total, user.getId().equals(callerId));
                })
                .toList();
    }

    /**
     * The creator's name as it is now, not as it was when the link was made. {@code creator_name}
     * is a snapshot, and reading it for a signed-in creator meant an admin who renamed an offensive
     * display name left the old one on every link that account had sent. The snapshot is only
     * what is left for an anonymous creator, or one whose account has been deleted — and
     * {@link ChallengeRepository#forgetCreator} has already replaced it in that case.
     */
    static String creatorName(Challenge challenge) {
        User creator = challenge.getCreator();
        if (creator == null) return challenge.getCreatorName();
        return creator.getDisplayName() == null ? creator.getShortWalletAddress() : creator.getDisplayName();
    }

    private Challenge find(String id) {
        return challenges.findById(id == null ? "" : id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thách đấu này."));
    }

    private static boolean isCreator(Challenge challenge, Long callerId) {
        return callerId != null && challenge.getCreator() != null && callerId.equals(challenge.getCreator().getId());
    }

    private ChallengeCreatedResponse created(Challenge challenge) {
        return new ChallengeCreatedResponse(challenge.getId(), "/?thach=" + challenge.getId(),
                challenge.getCreatorCorrect(), challenge.getTotalGuesses());
    }

    private String freshId() {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder id = new StringBuilder(ID_LENGTH);
            for (int i = 0; i < ID_LENGTH; i++) id.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            if (!challenges.existsById(id.toString())) return id.toString();
        }
        throw new IllegalStateException("Could not find a free challenge id");
    }
}

package com.example.candles.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.example.candles.entity.Asset;
import com.example.candles.entity.Direction;
import com.example.candles.entity.GuessResult;
import com.example.candles.repository.GuessResultRepository;
import com.example.candles.repository.UserRepository;

/**
 * Records answered guesses against the signed-in player.
 *
 * Practice stays playable without an account, so this is deliberately best-effort: no login
 * means nothing is written and the round proceeds exactly as before. Recording must never be
 * able to turn a correctly answered guess into a failed request — the player already saw the
 * answer by the time we get here.
 */
@Service
public class GuessResultService {

    private static final Logger log = LoggerFactory.getLogger(GuessResultService.class);

    private final GuessResultRepository guessResultRepository;
    private final UserRepository userRepository;

    public GuessResultService(GuessResultRepository guessResultRepository, UserRepository userRepository) {
        this.guessResultRepository = guessResultRepository;
        this.userRepository = userRepository;
    }

    /** {@code guessed} is null when the player ran out of time — recorded as an unanswered guess. */
    public void record(Asset asset, String timeframe, int startIndex, int guessNumber,
                       Direction guessed, Direction actual) {
        Long userId = currentUserId();
        if (userId == null) {
            return; // anonymous practice — nothing to attach the result to
        }
        if (guessResultRepository.alreadyRecorded(userId, asset.getId(), timeframe, startIndex, guessNumber)) {
            return; // same roundToken replayed within its TTL
        }

        userRepository.findById(userId).ifPresent(user -> {
            try {
                guessResultRepository.save(new GuessResult(
                        user, asset, timeframe, startIndex, guessNumber, guessed, actual));
            } catch (DataIntegrityViolationException e) {
                // Two requests for the same guess raced past the check above; the unique
                // constraint settled it, which is exactly what it is there for.
                log.debug("Duplicate guess result ignored for user {}", userId);
            }
        });
    }

    /**
     * JwtAuthenticationFilter authenticates as a bare user id. An unauthenticated request
     * carries Spring Security's anonymous token, whose principal is a String, so the pattern
     * match fails and we treat it as anonymous.
     */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Long userId) {
            return userId;
        }
        return null;
    }
}

package com.example.candles.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.ChallengeResult;
import com.example.candles.domain.RoundToken;
import com.example.candles.entity.GuessMode;
import com.example.candles.exception.InvalidRoundTokenException;

/**
 * Signs/verifies the roundToken JWT: it carries the round session pointer (asset, window
 * start, which guess we're on) so the server stays stateless between requests.
 */
@Service
public class RoundTokenService {

    private final SecretKey key;
    private final CandlesProperties properties;

    public RoundTokenService(CandlesProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.jwt().secret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * `iat` is kept for the expiry window, but the millisecond stamp beside it is what timing
     * is measured from. A JWT's `iat` is a NumericDate — whole seconds — so a token minted at
     * .900 comes back reading .000, and a guess sent immediately after looks 900ms old. That is
     * not a rounding nuisance: it is the anti-automation floor, and it made an instant answer
     * pass roughly two times in three depending only on when in the second the round was dealt.
     */
    public String generate(RoundToken token) {
        Instant now = Instant.now();
        return Jwts.builder()
                .claim("assetId", token.assetId())
                .claim("timeframe", token.timeframe())
                .claim("startIndex", token.startIndex())
                .claim("guessNumber", token.guessNumber())
                .claim("mode", token.mode().name())
                .claim("misses", token.misses())
                .claim("iatMs", now.toEpochMilli())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.jwt().ttl())))
                .signWith(key)
                .compact();
    }

    /** A result token lives a day: long enough to finish a chart and send the link later that evening. */
    private static final java.time.Duration CHALLENGE_RESULT_TTL = java.time.Duration.ofDays(1);
    private static final String CHALLENGE_RESULT = "challenge-result";

    /**
     * Signs how a finished practice chart went, for {@code POST /api/challenges}. A different
     * {@code type} claim from a round token, so neither can be passed off as the other.
     */
    public String generateChallengeResult(ChallengeResult result) {
        Instant now = Instant.now();
        return Jwts.builder()
                .claim("type", CHALLENGE_RESULT)
                .claim("assetId", result.assetId())
                .claim("timeframe", result.timeframe())
                .claim("startIndex", result.startIndex())
                .claim("correct", result.correct())
                .claim("total", result.total())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(CHALLENGE_RESULT_TTL)))
                .signWith(key)
                .compact();
    }

    public ChallengeResult verifyChallengeResult(String jwt) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(jwt).getPayload();
            if (!CHALLENGE_RESULT.equals(claims.get("type", String.class))) {
                throw new InvalidRoundTokenException("Không phải kết quả của một biểu đồ đã chơi xong.");
            }
            return new ChallengeResult(
                    claims.get("assetId", Number.class).longValue(),
                    claims.get("timeframe", String.class),
                    claims.get("startIndex", Number.class).intValue(),
                    claims.get("correct", Number.class).intValue(),
                    claims.get("total", Number.class).intValue());
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            throw new InvalidRoundTokenException(e);
        }
    }

    /**
     * The verified round together with when the token was minted — the clock the guess
     * deadline is measured against. Nothing else in the request can be trusted for timing:
     * the client's own idea of elapsed time is the thing being checked.
     */
    public record Verified(RoundToken round, Instant issuedAt) {
    }

    public Verified verify(String jwt) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(jwt)
                    .getPayload();
            /* A token minted before the claim existed can only be a practice one — the daily
               challenge did not exist then. Same reasoning as iatMs below: round tokens live
               minutes, so this window shuts on its own, and a player mid-round at deploy time
               should not have their next guess rejected. */
            if (claims.get("type") != null) {
                throw new InvalidRoundTokenException("Đây không phải token của một lượt chơi.");
            }
            String mode = claims.get("mode", String.class);
            Number misses = claims.get("misses", Number.class);
            RoundToken round = new RoundToken(
                    claims.get("assetId", Number.class).longValue(),
                    claims.get("timeframe", String.class),
                    claims.get("startIndex", Number.class).intValue(),
                    claims.get("guessNumber", Number.class).intValue(),
                    mode == null ? GuessMode.PRACTICE : GuessMode.valueOf(mode),
                    // A token from before hints existed has missed nothing as far as this can
                    // tell, which errs toward the harder chart rather than a free hint.
                    misses == null ? 0 : misses.intValue()
            );
            /* Falls back to `iat` for a token minted before this claim existed. Round tokens
               live minutes, so that window closes on its own — but a signed-in player mid-round
               at deploy time should not have their next guess rejected. */
            Number issuedMs = claims.get("iatMs", Number.class);
            Instant issuedAt = issuedMs != null
                    ? Instant.ofEpochMilli(issuedMs.longValue())
                    : claims.getIssuedAt().toInstant();
            return new Verified(round, issuedAt);
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidRoundTokenException(e);
        }
    }
}

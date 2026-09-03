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
import com.example.candles.domain.RoundToken;
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
                .claim("iatMs", now.toEpochMilli())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.jwt().ttl())))
                .signWith(key)
                .compact();
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
            RoundToken round = new RoundToken(
                    claims.get("assetId", Number.class).longValue(),
                    claims.get("timeframe", String.class),
                    claims.get("startIndex", Number.class).intValue(),
                    claims.get("guessNumber", Number.class).intValue()
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

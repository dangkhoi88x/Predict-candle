package com.example.candles.round;

import com.example.candles.config.CandlesProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

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

    public String generate(RoundToken token) {
        Instant now = Instant.now();
        return Jwts.builder()
                .claim("assetId", token.assetId())
                .claim("timeframe", token.timeframe())
                .claim("startIndex", token.startIndex())
                .claim("guessNumber", token.guessNumber())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.jwt().ttl())))
                .signWith(key)
                .compact();
    }

    public RoundToken verify(String jwt) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(jwt)
                    .getPayload();
            return new RoundToken(
                    claims.get("assetId", Number.class).longValue(),
                    claims.get("timeframe", String.class),
                    claims.get("startIndex", Number.class).intValue(),
                    claims.get("guessNumber", Number.class).intValue()
            );
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidRoundTokenException(e);
        }
    }
}

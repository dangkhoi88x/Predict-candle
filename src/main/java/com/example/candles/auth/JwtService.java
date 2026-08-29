package com.example.candles.auth;

import com.example.candles.config.AuthProperties;
import com.example.candles.domain.User;
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
 * Signs/verifies both access and refresh tokens with the same key, distinguished by a
 * "type" claim. Stateless: a refresh token is valid until it expires, there's no server-side
 * revocation list — logout only clears the browser's cookie.
 */
@Service
public class JwtService {

    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final AuthProperties properties;

    public JwtService(AuthProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.jwt().secret().getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(User user) {
        return createToken(user, TYPE_ACCESS, properties.jwt().accessTokenTtl());
    }

    public String createRefreshToken(User user) {
        return createToken(user, TYPE_REFRESH, properties.jwt().refreshTokenTtl());
    }

    public Long parseAccessToken(String token) {
        return parseToken(token, TYPE_ACCESS);
    }

    public Long parseRefreshToken(String token) {
        return parseToken(token, TYPE_REFRESH);
    }

    private String createToken(User user, String type, java.time.Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("type", type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    private Long parseToken(String token, String expectedType) {
        if (token == null || token.isBlank()) {
            throw new InvalidRefreshTokenException();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (!expectedType.equals(claims.get("type", String.class))) {
                throw new InvalidRefreshTokenException();
            }
            return Long.valueOf(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidRefreshTokenException();
        }
    }
}

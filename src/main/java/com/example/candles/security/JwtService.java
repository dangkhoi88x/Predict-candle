package com.example.candles.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import com.example.candles.config.AuthProperties;
import com.example.candles.entity.Role;
import com.example.candles.entity.User;
import com.example.candles.exception.InvalidRefreshTokenException;

/**
 * Signs/verifies both access and refresh tokens with the same key, distinguished by a
 * "type" claim.
 *
 * Access tokens stay purely stateless — they last fifteen minutes, and checking a database
 * row on every request to shorten that window is a poor trade. Refresh tokens are different:
 * they last thirty days, so they carry the account's token version and the server rejects any
 * that were minted under an older one. That is what makes logout mean something.
 */
@Service
public class JwtService {

    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";
    private static final String CLAIM_VERSION = "ver";
    private static final String CLAIM_ROLE = "role";

    private final SecretKey key;
    private final AuthProperties properties;

    public JwtService(AuthProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.jwt().secret().getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("type", TYPE_ACCESS)
                /*
                 * The role travels in the token so authorization costs nothing per request.
                 * The price is that it is a snapshot: a demotion is only guaranteed to take
                 * effect once the current access token expires. User.assignRole revokes the
                 * account's refresh tokens for exactly that reason, capping the gap at the
                 * access token's fifteen minutes, and anything that actually writes re-reads
                 * the role from the database (see AdminAccess).
                 */
                .claim(CLAIM_ROLE, user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.jwt().accessTokenTtl())))
                .signWith(key)
                .compact();
    }

    public String createRefreshToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("type", TYPE_REFRESH)
                .claim(CLAIM_VERSION, user.getTokenVersion())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.jwt().refreshTokenTtl())))
                .signWith(key)
                .compact();
    }

    /** The account the access token names, and the role it was carrying when it was issued. */
    public AccessClaims parseAccessToken(String token) {
        Claims claims = verifiedClaims(token, TYPE_ACCESS);
        String role = claims.get(CLAIM_ROLE, String.class);
        return new AccessClaims(
                Long.valueOf(claims.getSubject()),
                // Tokens minted before roles existed carry none, and the safe reading of a
                // missing role is the one with no privileges.
                role == null ? Role.USER : parseRole(role));
    }

    private static Role parseRole(String name) {
        try {
            return Role.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Role.USER;
        }
    }

    public record AccessClaims(Long userId, Role role) {
    }

    /** Returns the user id and the token version the refresh token was minted under. */
    public RefreshClaims parseRefreshToken(String token) {
        Claims claims = verifiedClaims(token, TYPE_REFRESH);
        /*
         * Read as Number, not Integer. This project deserializes with Gson (to stay clear of
         * Jackson 3), and Gson hands every JSON number back as a Double — asking jjwt for an
         * Integer throws RequiredTypeException.
         *
         * A missing claim counts as version zero: that is what tokens minted before this
         * existed carry, and what an account that has never logged out sits at.
         */
        Object version = claims.get(CLAIM_VERSION);
        int tokenVersion = version instanceof Number number ? number.intValue() : 0;
        return new RefreshClaims(Long.valueOf(claims.getSubject()), tokenVersion);
    }

    public record RefreshClaims(Long userId, int tokenVersion) {
    }

    private Claims verifiedClaims(String token, String expectedType) {
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
            return claims;
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidRefreshTokenException();
        }
    }
}

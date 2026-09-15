package com.example.candles.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.candles.config.AuthProperties;
import com.example.candles.domain.AuthSession;
import com.example.candles.dto.request.TelegramLoginRequest;
import com.example.candles.dto.request.WalletVerifyRequest;
import com.example.candles.dto.response.AuthResponse;
import com.example.candles.dto.response.WalletNonceResponse;
import com.example.candles.entity.User;
import com.example.candles.exception.InvalidCredentialsException;
import com.example.candles.security.TelegramInitDataVerifier;
import com.example.candles.service.AuthService;
import com.example.candles.service.RateLimiter;

@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    private static final String REFRESH_COOKIE_NAME = "refresh_token";

    private final AuthService authService;
    private final AuthProperties authProperties;
    private final TelegramInitDataVerifier telegramVerifier;
    private final RateLimiter rateLimiter;

    public AuthController(AuthService authService, AuthProperties authProperties,
                          TelegramInitDataVerifier telegramVerifier, RateLimiter rateLimiter) {
        this.authService = authService;
        this.authProperties = authProperties;
        this.telegramVerifier = telegramVerifier;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/wallet/nonce")
    public WalletNonceResponse nonce(@RequestParam @Pattern(regexp = "^0x[a-fA-F0-9]{40}$") String address) {
        return authService.issueNonce(address);
    }

    @PostMapping("/wallet/verify")
    public ResponseEntity<AuthResponse> verify(@Valid @RequestBody WalletVerifyRequest request) {
        return withRefreshCookie(HttpStatus.OK, authService.walletLogin(request));
    }

    /**
     * Sign-in for a player who opened the game as a Telegram Mini App: the launch's signed
     * {@code initData}, checked against the bot token. 404 when no bot is configured, so a
     * deployment without Telegram does not advertise a login that cannot work.
     */
    @PostMapping("/telegram")
    public ResponseEntity<AuthResponse> telegram(@Valid @RequestBody TelegramLoginRequest request,
                                                 HttpServletRequest httpRequest) {
        if (!telegramVerifier.enabled()) return ResponseEntity.notFound().build();
        rateLimiter.check("telegram-login", 30, httpRequest);
        return withRefreshCookie(HttpStatus.OK, authService.telegramLogin(telegramVerifier.verify(request.initData())));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshToken) {
        return withRefreshCookie(HttpStatus.OK, authService.refresh(refreshToken));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshToken) {
        authService.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", 0).toString())
                .build();
    }

    @GetMapping("/me")
    public AuthResponse me(Authentication authentication) {
        if (authentication == null) {
            throw new InvalidCredentialsException();
        }
        Long userId = (Long) authentication.getPrincipal();
        User user = authService.currentUser(userId);
        return AuthResponse.from(user, null);
    }

    private ResponseEntity<AuthResponse> withRefreshCookie(HttpStatus status, AuthSession session) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookie(session.refreshToken(), authProperties.jwt().refreshTokenTtl().toSeconds()).toString())
                .body(session.response());
    }

    private ResponseCookie refreshCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(REFRESH_COOKIE_NAME, value)
                .httpOnly(true)
                .secure(authProperties.cookie().secure())
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(maxAgeSeconds)
                .build();
    }
}

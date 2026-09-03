package com.example.candles.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.candles.domain.AuthSession;
import com.example.candles.dto.request.WalletVerifyRequest;
import com.example.candles.dto.response.AuthResponse;
import com.example.candles.dto.response.WalletNonceResponse;
import com.example.candles.entity.Role;
import com.example.candles.entity.User;
import com.example.candles.exception.InvalidCredentialsException;
import com.example.candles.exception.InvalidRefreshTokenException;
import com.example.candles.repository.UserRepository;
import com.example.candles.security.AdminWallets;
import com.example.candles.security.JwtService;
import com.example.candles.security.WalletSignatureVerifier;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final NonceService nonceService;
    private final WalletSignatureVerifier signatureVerifier;
    private final AdminWallets adminWallets;

    public AuthService(UserRepository userRepository, JwtService jwtService, NonceService nonceService,
                        WalletSignatureVerifier signatureVerifier, AdminWallets adminWallets) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.nonceService = nonceService;
        this.signatureVerifier = signatureVerifier;
        this.adminWallets = adminWallets;
    }

    public WalletNonceResponse issueNonce(String rawAddress) {
        String address = normalizeAddress(rawAddress);
        String nonce = nonceService.issue(address);
        return new WalletNonceResponse(address, nonce, nonceService.buildMessage(address, nonce));
    }

    @Transactional
    public AuthSession walletLogin(WalletVerifyRequest request) {
        String address = normalizeAddress(request.address());
        String nonce;
        try {
            nonce = nonceService.consume(address);
        } catch (InvalidCredentialsException e) {
            log.warn("Wallet login rejected for {}: no pending nonce (missing/expired/already used)", address);
            throw e;
        }
        String message = nonceService.buildMessage(address, nonce);
        if (!signatureVerifier.matches(message, request.signature(), address)) {
            log.warn("Wallet login rejected for {}: signature does not recover to claimed address (signature={})",
                    address, request.signature());
            throw new InvalidCredentialsException();
        }

        User user = userRepository.findByWalletAddress(address)
                .orElseGet(() -> userRepository.save(new User(address, shortAddress(address))));

        /*
         * A wallet named in candles.admin.wallets that has never signed in has no row for the
         * startup reconciler to promote, so the first login is where it gets its role. Doing
         * it here as well means the config list never has to be applied in a given order.
         */
        if (adminWallets.grantsAdmin(address) && user.assignRole(Role.ADMIN)) {
            log.info("Promoted {} to ADMIN on login (wallet is in candles.admin.wallets)", address);
            userRepository.save(user);
        }
        return issueSession(user);
    }

    @Transactional
    public AuthSession refresh(String rawRefreshToken) {
        JwtService.RefreshClaims claims = jwtService.parseRefreshToken(rawRefreshToken);
        User user = userRepository.findById(claims.userId()).orElseThrow(InvalidRefreshTokenException::new);
        if (claims.tokenVersion() != user.getTokenVersion()) {
            log.warn("Refresh rejected for user {}: token version {}, account is at {}",
                    user.getId(), claims.tokenVersion(), user.getTokenVersion());
            throw new InvalidRefreshTokenException();
        }
        return issueSession(user);
    }

    /**
     * Ends every session for the account the refresh token belongs to, not just this browser.
     * Signing out on a shared machine should not leave a copied cookie working for a month.
     *
     * Tolerates a missing or unusable token: the caller is on their way out either way, and
     * the cookie gets cleared regardless.
     */
    @Transactional
    public void logout(String rawRefreshToken) {
        try {
            JwtService.RefreshClaims claims = jwtService.parseRefreshToken(rawRefreshToken);
            userRepository.findById(claims.userId()).ifPresent(user -> {
                user.revokeSessions();
                userRepository.save(user);
            });
        } catch (InvalidRefreshTokenException e) {
            log.debug("Logout without a usable refresh token; nothing to revoke");
        } catch (RuntimeException e) {
            // Anything else is a real failure and the sessions are still live. Logging out
            // the browser regardless is the lesser problem, but this must not pass silently
            // the way a catch-all would.
            log.error("Failed to revoke sessions on logout", e);
        }
    }

    @Transactional(readOnly = true)
    public User currentUser(Long userId) {
        return userRepository.findById(userId).orElseThrow(InvalidCredentialsException::new);
    }

    private AuthSession issueSession(User user) {
        String accessToken = jwtService.createAccessToken(user);
        String refreshToken = jwtService.createRefreshToken(user);
        return new AuthSession(AuthResponse.from(user, accessToken), refreshToken);
    }

    private String normalizeAddress(String address) {
        return address.toLowerCase(java.util.Locale.ROOT);
    }

    private String shortAddress(String address) {
        return address.substring(0, 6) + "…" + address.substring(address.length() - 4);
    }
}

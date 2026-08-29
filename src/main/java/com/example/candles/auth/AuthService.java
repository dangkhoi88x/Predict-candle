package com.example.candles.auth;

import com.example.candles.domain.User;
import com.example.candles.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final NonceService nonceService;
    private final WalletSignatureVerifier signatureVerifier;

    public AuthService(UserRepository userRepository, JwtService jwtService, NonceService nonceService,
                        WalletSignatureVerifier signatureVerifier) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.nonceService = nonceService;
        this.signatureVerifier = signatureVerifier;
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
        return issueSession(user);
    }

    @Transactional
    public AuthSession refresh(String rawRefreshToken) {
        Long userId = jwtService.parseRefreshToken(rawRefreshToken);
        User user = userRepository.findById(userId).orElseThrow(InvalidRefreshTokenException::new);
        return issueSession(user);
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

package com.example.candles.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.example.candles.entity.User;
import com.example.candles.repository.UserRepository;

/**
 * Re-reads the caller's role from the database instead of trusting the one in their access
 * token.
 *
 * The URL rules in SecurityConfig already keep non-admins out, and they run on the token's
 * claim, which is fast and right almost all of the time. "Almost" is the gap this closes: a
 * token minted before a demotion still says ADMIN for up to fifteen minutes. That is an
 * acceptable risk for reading a page and not for writing to shared storage, so anything that
 * writes calls this first. Admin actions are rare enough that the extra query is free.
 */
@Component
public class AdminAccess {

    private final UserRepository userRepository;

    public AdminAccess(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User requireAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
            throw new AccessDeniedException("Cần đăng nhập để dùng chức năng này.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AccessDeniedException("Tài khoản không còn tồn tại."));
        if (!user.isAdmin()) {
            throw new AccessDeniedException("Tài khoản này không có quyền quản trị.");
        }
        return user;
    }
}

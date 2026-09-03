package com.example.candles.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.candles.entity.User;
import com.example.candles.security.AdminAccess;

/**
 * The admin surface. Behind {@code hasRole("ADMIN")} in SecurityConfig, so a caller who is not
 * an admin never reaches a method here — they get a 403 with the same JSON shape as any other
 * error.
 *
 * The admin page asks this first to decide what to draw. That answer is a convenience for the
 * interface and nothing more: hiding a control in the browser is not access control, and every
 * endpoint added here has to hold on its own.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminAccess adminAccess;

    public AdminController(AdminAccess adminAccess) {
        this.adminAccess = adminAccess;
    }

    @GetMapping("/me")
    public AdminIdentity me() {
        User user = adminAccess.requireAdmin();
        return new AdminIdentity(user.getId(), user.getWalletAddress(), user.getDisplayName(),
                user.getRole().name());
    }

    public record AdminIdentity(Long userId, String walletAddress, String displayName, String role) {
    }
}

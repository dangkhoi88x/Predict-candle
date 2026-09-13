package com.example.candles.controller;

import com.example.candles.dto.response.AdminDemoAccount;
import com.example.candles.dto.response.AdminDemoOverview;
import com.example.candles.security.AdminAccess;
import com.example.candles.service.AdminDemoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Paper trading, read from outside a player's own account.
 *
 * One write, and it is the player's own reset rather than a second way of doing it: an admin
 * rewinding somebody's account undoes a position without erasing a history, because a reset
 * moves {@code opened_at} and deletes nothing. There is deliberately nothing here that credits
 * an account, edits a fill or changes a balance — the balance is not a column, and a screen
 * that could adjust one would be inventing a second source of truth this feature was built to
 * avoid.
 */
@RestController
@RequestMapping("/api/admin/demo")
public class AdminDemoController {

    private final AdminDemoService demo;
    private final AdminAccess adminAccess;

    public AdminDemoController(AdminDemoService demo, AdminAccess adminAccess) {
        this.demo = demo;
        this.adminAccess = adminAccess;
    }

    @GetMapping
    public AdminDemoOverview overview(@RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size) {
        return demo.overview(page, size);
    }

    @GetMapping("/{userId}")
    public AdminDemoAccount account(@PathVariable Long userId) {
        return demo.account(userId);
    }

    @PostMapping("/{userId}/reset")
    public AdminDemoAccount reset(@PathVariable Long userId) {
        adminAccess.requireAdmin();
        return demo.reset(userId);
    }
}

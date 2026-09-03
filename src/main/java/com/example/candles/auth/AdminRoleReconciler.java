package com.example.candles.auth;

import com.example.candles.domain.Role;
import com.example.candles.domain.User;
import com.example.candles.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Makes the database agree with candles.admin.wallets at every startup: accounts on the list
 * are promoted, accounts holding ADMIN that are no longer on it are demoted.
 *
 * The demotion half is the point. Promoting only would mean removing a wallet from the config
 * quietly did nothing, and revoking someone's access has to be as easy as granting it. The
 * cost is that the config is the only way to grant ADMIN — a future admin screen cannot hand
 * the role out, because this would take it back on the next restart. That is the intended
 * trade for now; when it stops being, this class is the thing to change.
 *
 * A configured wallet that has never signed in has no row yet. AuthService promotes it at
 * login instead, so the list does not have to be applied in a particular order.
 */
@Component
public class AdminRoleReconciler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminRoleReconciler.class);

    private final UserRepository userRepository;
    private final AdminWallets adminWallets;

    public AdminRoleReconciler(UserRepository userRepository, AdminWallets adminWallets) {
        this.userRepository = userRepository;
        this.adminWallets = adminWallets;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (adminWallets.all().isEmpty()) {
            log.info("No candles.admin.wallets configured — every /api/admin route is closed");
        }

        List<User> promoted = userRepository.findByWalletAddressIn(adminWallets.all()).stream()
                .filter(user -> user.assignRole(Role.ADMIN))
                .toList();

        List<User> demoted = userRepository.findByRole(Role.ADMIN).stream()
                .filter(user -> !adminWallets.grantsAdmin(user.getWalletAddress()))
                .filter(user -> user.assignRole(Role.USER))
                .toList();

        if (!promoted.isEmpty() || !demoted.isEmpty()) {
            userRepository.saveAll(promoted);
            userRepository.saveAll(demoted);
            log.info("Admin roles reconciled: {} promoted, {} demoted", promoted.size(), demoted.size());
        }
    }
}

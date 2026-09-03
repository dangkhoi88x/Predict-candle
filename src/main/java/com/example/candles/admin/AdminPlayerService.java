package com.example.candles.admin;

import com.example.candles.domain.User;
import com.example.candles.repository.GuessResultRepository;
import com.example.candles.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The player list, and the two things an admin legitimately needs to do to an account: fix a
 * display name, and delete it on request.
 *
 * Deliberately no way to grant a role or edit anyone's score. Roles come from configuration,
 * and an admin who can adjust totals makes every ranking meaningless.
 */
@Service
public class AdminPlayerService {

    private final UserRepository userRepository;
    private final GuessResultRepository guessResultRepository;

    public AdminPlayerService(UserRepository userRepository, GuessResultRepository guessResultRepository) {
        this.userRepository = userRepository;
        this.guessResultRepository = guessResultRepository;
    }

    @Transactional(readOnly = true)
    public List<PlayerSummary> players() {
        // One grouped query instead of a count per row: the list is short today and this is
        // the shape that stays fine when it is not.
        Map<Long, Object[]> tallies = new HashMap<>();
        for (Object[] row : guessResultRepository.tallyByUser()) {
            tallies.put(((Number) row[0]).longValue(), row);
        }

        return userRepository.findAll().stream()
                .map(user -> {
                    Object[] tally = tallies.get(user.getId());
                    return new PlayerSummary(
                            user.getId(), user.getWalletAddress(), user.getDisplayName(),
                            user.getRole().name(),
                            tally == null ? 0 : ((Number) tally[1]).longValue(),
                            tally == null ? 0 : ((Number) tally[2]).longValue(),
                            user.hasImportedLegacyStats(),
                            user.getCreatedAt(),
                            tally == null ? null : (Instant) tally[3]);
                })
                .sorted((a, b) -> Long.compare(b.guesses(), a.guesses()))
                .toList();
    }

    @Transactional
    public PlayerSummary rename(Long userId, String displayName) {
        String trimmed = displayName == null ? "" : displayName.trim();
        if (trimmed.isEmpty() || trimmed.length() > 100) {
            throw new IllegalArgumentException("Tên hiển thị phải từ 1 đến 100 ký tự.");
        }
        User user = require(userId);
        user.setDisplayName(trimmed);
        userRepository.save(user);
        return players().stream().filter(p -> p.id().equals(userId)).findFirst().orElseThrow();
    }

    /**
     * Removes the account and everything recorded against it. Guess results are deleted first
     * because they hold the foreign key — and because leaving them would keep the player's
     * history under a user id nobody can look up.
     */
    @Transactional
    public void delete(Long userId) {
        User user = require(userId);
        if (user.isAdmin()) {
            throw new IllegalArgumentException(
                    "Không xoá được tài khoản admin. Gỡ ví khỏi candles.admin.wallets rồi khởi động lại trước.");
        }
        guessResultRepository.deleteByUserId(userId);
        userRepository.delete(user);
    }

    private User require(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản #" + userId));
    }
}

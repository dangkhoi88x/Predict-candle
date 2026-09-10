package com.example.candles.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

import com.example.candles.dto.response.AdminPlayerDetail;
import com.example.candles.dto.response.AdminPlayerPage;
import com.example.candles.dto.response.PlayerSummary;
import com.example.candles.entity.User;
import com.example.candles.repository.GuessResultRepository;
import com.example.candles.repository.LivePredictionRepository;
import com.example.candles.repository.UserRepository;

/**
 * The player list, one account in detail, and the two things an admin legitimately needs to do
 * to an account: fix a display name, and delete it on request.
 *
 * Deliberately no way to grant a role or edit anyone's score. Roles come from configuration,
 * and an admin who can adjust totals makes every ranking meaningless. The detail view keeps
 * that bargain: it reads the same rows the game scores on and offers nothing to change them
 * with.
 */
@Service
public class AdminPlayerService {

    /** Enough rows to see a session's shape without paging, few enough to read at a glance. */
    private static final int RECENT_ROWS = 25;

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final UserRepository userRepository;
    private final GuessResultRepository guessResultRepository;
    private final LivePredictionRepository livePredictionRepository;

    public AdminPlayerService(UserRepository userRepository,
                              GuessResultRepository guessResultRepository,
                              LivePredictionRepository livePredictionRepository) {
        this.userRepository = userRepository;
        this.guessResultRepository = guessResultRepository;
        this.livePredictionRepository = livePredictionRepository;
    }

    /**
     * One page of accounts, ordered by how much they have played unless asked for the most
     * recent.
     *
     * The tally is joined in the query rather than fetched alongside the page, because it is
     * what the page is ordered by: sorting in Java would mean reading every account first,
     * which is exactly the shape this replaced.
     */
    @Transactional(readOnly = true)
    public AdminPlayerPage players(String query, String sort, Integer page, Integer size) {
        String trimmed = query == null ? "" : query.trim();
        /* Null rather than an empty string, because the query tests for null: a `like '%%'`
           would match every row anyway, but only by doing the work of a scan per column. */
        String pattern = trimmed.isEmpty() ? null : "%" + trimmed.toLowerCase(Locale.ROOT) + "%";
        String order = "recent".equals(sort) ? "recent" : "active";
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : Math.clamp(size, 1, MAX_PAGE_SIZE);
        int pageIndex = page == null ? 0 : Math.max(page, 0);

        List<PlayerSummary> players = userRepository
                .playerPage(pattern, order, pageSize, pageIndex * pageSize).stream()
                .map(AdminPlayerService::summaryOf)
                .toList();
        long total = userRepository.countPlayers(pattern);

        return new AdminPlayerPage(players, trimmed, order, pageIndex, pageSize, total,
                (long) pageIndex * pageSize + players.size() < total);
    }

    /**
     * Everything recorded against one account.
     *
     * Five reads rather than one, and they stay five: each answers a different question — how
     * the history divides by game, by pair, what the live calls did, what was imported, and
     * what the last few rounds actually looked like. Folding them into one query would produce
     * a shape nothing else wants and a join nobody could read.
     */
    @Transactional(readOnly = true)
    public AdminPlayerDetail detail(Long userId) {
        User user = require(userId);

        List<AdminPlayerDetail.ModeTally> modes = guessResultRepository.tallyByMode(userId).stream()
                .map(row -> new AdminPlayerDetail.ModeTally(
                        String.valueOf(row[0]), asLong(row[1]), asLong(row[2])))
                .toList();

        List<AdminPlayerDetail.AssetTally> assets = guessResultRepository.tallyByAsset(userId).stream()
                .map(row -> new AdminPlayerDetail.AssetTally(
                        String.valueOf(row[0]), asLong(row[1]), asLong(row[2])))
                .toList();

        Object[] liveRow = unwrap(livePredictionRepository.liveTallyForUser(userId));
        AdminPlayerDetail.LiveTally live = new AdminPlayerDetail.LiveTally(
                asLong(liveRow[0]), asLong(liveRow[1]), asLong(liveRow[2]));

        AdminPlayerDetail.Legacy legacy = user.hasImportedLegacyStats()
                ? new AdminPlayerDetail.Legacy(user.getLegacyTotal(), user.getLegacyCorrect(),
                        user.getLegacyScore(), user.getLegacyBestStreak(), user.getLegacyImportedAt())
                : null;

        List<AdminPlayerDetail.Guess> guesses = guessResultRepository
                .findRecent(userId, PageRequest.of(0, RECENT_ROWS)).stream()
                .map(g -> new AdminPlayerDetail.Guess(g.getCreatedAt(), g.getMode().name(),
                        g.getAsset().getSymbol(), g.getGuessNumber(),
                        g.getGuessedDirection() == null ? null : g.getGuessedDirection().name(),
                        g.getActualDirection().name(), g.isCorrect()))
                .toList();

        List<AdminPlayerDetail.LiveCall> calls = livePredictionRepository
                .recentCallsForUser(userId, RECENT_ROWS).stream()
                .map(row -> new AdminPlayerDetail.LiveCall(instant(row[0]), instant(row[1]),
                        String.valueOf(row[2]), String.valueOf(row[3]), (Boolean) row[4]))
                .toList();

        return new AdminPlayerDetail(summary(user), user.getCreatedAt(),
                modes, assets, live, legacy, guesses, calls);
    }

    @Transactional
    public PlayerSummary rename(Long userId, String displayName) {
        String trimmed = displayName == null ? "" : displayName.trim();
        if (trimmed.isEmpty() || trimmed.length() > 100) {
            throw new IllegalArgumentException("Tên hiển thị phải từ 1 đến 100 ký tự.");
        }
        User user = require(userId);
        user.setDisplayName(trimmed);
        return summary(userRepository.save(user));
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

    /**
     * One account's summary, tallied on its own.
     *
     * Used after a rename and by the detail view, both of which are about a single account —
     * reading a page of the list to find one row in it, which is what this replaced, ran the
     * whole ordered query to throw all but one row away.
     */
    private PlayerSummary summary(User user) {
        Object[] row = unwrap(guessResultRepository.tallyForUser(user.getId()));
        return new PlayerSummary(user.getId(), user.getWalletAddress(), user.getDisplayName(),
                user.getRole().name(), asLong(row[0]), asLong(row[1]),
                user.hasImportedLegacyStats(), user.getCreatedAt(), (Instant) row[2]);
    }

    /** One row of {@link UserRepository#playerPage}, in that query's column order. */
    private static PlayerSummary summaryOf(Object[] row) {
        return new PlayerSummary(
                asLong(row[0]), String.valueOf(row[1]), String.valueOf(row[2]), String.valueOf(row[3]),
                asLong(row[5]), asLong(row[6]), asBoolean(row[8]),
                instant(row[4]), instant(row[7]));
    }

    /**
     * The bucket columns come back as zoneless timestamps on some drivers and as instants on
     * others; the same conversion {@code AdminStatsService} makes, for the same reason.
     */
    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof Instant already) return already;
        if (value instanceof java.time.OffsetDateTime offset) return offset.toInstant();
        if (value instanceof java.time.LocalDateTime local) return local.toInstant(ZoneOffset.UTC);
        throw new IllegalStateException("Unexpected timestamp type: " + value.getClass());
    }

    private static Object[] unwrap(Object[] row) {
        if (row.length == 1 && row[0] instanceof Object[] inner) {
            return inner;
        }
        return row;
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static boolean asBoolean(Object value) {
        return value instanceof Boolean flag && flag;
    }
}

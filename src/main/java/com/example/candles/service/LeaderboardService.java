package com.example.candles.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.candles.domain.PlayerScore;
import com.example.candles.dto.response.Leaderboard;
import com.example.candles.entity.Role;
import com.example.candles.entity.User;
import com.example.candles.repository.LivePredictionRepository;
import com.example.candles.repository.UserRepository;

/**
 * Builds the public leaderboard.
 *
 * Ranking runs on {@link PlayerScore}, the same function the profile and the game tab score
 * with, so a player's rank is computed from exactly the number they already see. That is also
 * what forces the shape of the query below: score depends on the order guesses were made, so
 * it cannot be reached with SUM or COUNT, and every player's history has to be walked.
 *
 * One player, one score, whichever tab they called it from: the flags this ranks on are
 * practice guesses and settled live-round calls combined — see
 * {@link LivePredictionRepository#combinedResultFlagsByUserInPlayOrder()} for how those two
 * tables get interleaved by when each call was actually made. One query does that for everyone
 * at once, ordered by (user, time) so it rides the index that already exists for the per-player
 * read. The whole board is then cached for a minute, the same arrangement {@code
 * AdminStatsService} uses — this is a public endpoint anyone can call, and recomputing it per
 * request would put a full scan of both tables behind an open URL.
 */
@Service
public class LeaderboardService {

    /**
     * Below this, a rank means nothing: two correct guesses out of two is a perfect record and
     * a meaningless one. It also keeps a brand-new account from displacing someone with a
     * thousand guesses on the strength of a lucky afternoon.
     */
    public static final int MIN_GUESSES = 20;

    private static final int MAX_LIMIT = 200;
    private static final String CACHE_KEY = "board";

    private final LivePredictionRepository livePredictions;
    private final UserRepository users;

    /** Holds the full ranking; a request's limit is applied after the cache, not inside it. */
    private final Cache<String, List<Ranked>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

    public LeaderboardService(LivePredictionRepository livePredictions, UserRepository users) {
        this.livePredictions = livePredictions;
        this.users = users;
    }

    /** One player's standing, before it is trimmed to a page. */
    private record Ranked(Long userId, Leaderboard.Row row) {
    }

    @Transactional(readOnly = true)
    public Leaderboard board(int limit, Long callerId) {
        List<Ranked> ranked = cache.get(CACHE_KEY, key -> rank());

        int size = Math.clamp(limit, 1, MAX_LIMIT);
        List<Leaderboard.Row> page = ranked.stream().limit(size).map(Ranked::row).toList();

        /* Being told "you are 47th" is the reason to open this twice, so the caller's own row
           is resolved against the whole ranking rather than the page — otherwise everyone
           outside the top N sees nothing about themselves. */
        Leaderboard.Row me = callerId == null ? null : ranked.stream()
                .filter(r -> callerId.equals(r.userId()))
                .map(Ranked::row)
                .findFirst().orElse(null);

        return new Leaderboard(Instant.now(), MIN_GUESSES, page, me);
    }

    /** Drops the cached ranking, so the next read rebuilds it. */
    public void evict() {
        cache.invalidateAll();
    }

    private List<Ranked> rank() {
        // Rows arrive already grouped and in play order — practice and settled live-round
        // calls interleaved by when each was made — so one pass fills the per-player lists.
        Map<Long, List<Boolean>> flags = new LinkedHashMap<>();
        for (Object[] row : livePredictions.combinedResultFlagsByUserInPlayOrder()) {
            Long userId = ((Number) row[0]).longValue();
            flags.computeIfAbsent(userId, id -> new ArrayList<>())
                    .add(Boolean.TRUE.equals(row[1]));
        }

        /* Admin accounts are excluded here rather than filtered later: the seeded/dev admin
           wallet plays far more rounds than any real player while testing the app, and a public
           board that shows it in first place reads as staff gaming their own leaderboard —
           which is worse than an empty board. Left out of `names` entirely, an admin's rows
           fall through the same "nobody to rank" branch below that already handles a deleted
           user, so there is exactly one place that decides who counts. */
        Map<Long, String> names = new HashMap<>();
        for (User user : users.findAllById(flags.keySet())) {
            if (user.getRole() == Role.ADMIN) continue;
            names.put(user.getId(), user.getDisplayName());
        }

        record Scored(Long userId, String name, PlayerScore score) {
        }

        List<Scored> qualified = new ArrayList<>();
        flags.forEach((userId, results) -> {
            if (results.size() < MIN_GUESSES) return;
            String name = names.get(userId);
            // Absent here means either the user has since been deleted, or is an admin — both
            // have nobody to rank.
            if (name == null) return;
            qualified.add(new Scored(userId, name, PlayerScore.of(results)));
        });

        /* Score first. Ties break on accuracy then on fewer guesses, so reaching the same score
           in less play ranks higher — otherwise two equal scores would order arbitrarily and
           shuffle between refreshes. */
        qualified.sort(Comparator
                .comparingLong((Scored s) -> s.score().score()).reversed()
                .thenComparing(Comparator.comparingDouble((Scored s) -> accuracy(s.score())).reversed())
                .thenComparingLong(s -> s.score().total())
                .thenComparing(Scored::name));

        List<Ranked> out = new ArrayList<>(qualified.size());
        for (int i = 0; i < qualified.size(); i++) {
            Scored s = qualified.get(i);
            PlayerScore p = s.score();
            out.add(new Ranked(s.userId(), new Leaderboard.Row(
                    i + 1, s.name(), p.score(), p.total(), p.correct(),
                    p.total() == 0 ? null : (double) p.correct() / p.total(),
                    p.bestStreak())));
        }
        return out;
    }

    private static double accuracy(PlayerScore p) {
        return p.total() == 0 ? 0 : (double) p.correct() / p.total();
    }
}

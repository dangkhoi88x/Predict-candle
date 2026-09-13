package com.example.candles.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.example.candles.entity.Role;
import com.example.candles.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByWalletAddress(String walletAddress);

    List<User> findByWalletAddressIn(Collection<String> walletAddresses);

    List<User> findByRole(Role role);

    /**
     * New accounts per day, UTC, for the admin overview. Rows of [dayStart, count].
     *
     * The account KPI shows a running total, so what a sparkline under it has to draw is that
     * same total over time — which is this series added back onto today's count, walked
     * backwards. Counting signups instead of active players is the difference between a delta
     * that describes the number above it and one that describes something else.
     */
    @Query(value = "select date_trunc('day', u.created_at at time zone 'UTC') as day, count(*)"
            + " from users u where u.created_at >= :since and u.created_at < :until"
            + " group by day order by day", nativeQuery = true)
    List<Object[]> signupsByDay(@Param("since") Instant since, @Param("until") Instant until);

    /**
     * One page of the admin player list, as rows of
     * [id, wallet, displayName, role, createdAt, guesses, correct, lastPlayedAt, legacyImported].
     *
     * The tally is joined in rather than fetched beside the page, because it is what the list is
     * ordered by. Reading every account into memory to sort them — which is what this replaced —
     * works until the day it does not, and the failure is a page that gets slower every week with
     * nothing on screen to say why.
     *
     * {@code sort} is not user text: the caller maps a fixed enum onto 'active' or 'recent'. The
     * branch for the one not chosen evaluates to null on every row, so it ties and the next key
     * decides — which is how two orderings live in one query without two queries.
     *
     * Search is {@code like} over the lowered address and display name. No diacritic folding,
     * unlike the topbar search: that one matches rendered DOM text where a Vietnamese title is
     * the common case, and this matches an address or a name somebody is pasting in.
     */
    @Query(value = """
            select u.id, u.wallet_address, u.display_name, u.role, u.created_at,
                   coalesce(g.total, 0), coalesce(g.correct, 0), g.last_played,
                   (u.legacy_imported_at is not null)
            from users u
            left join (
                select user_id,
                       count(*) as total,
                       count(*) filter (where correct) as correct,
                       max(created_at) as last_played
                from guess_results group by user_id
            ) g on g.user_id = u.id
            where cast(:pattern as text) is null
               or lower(u.wallet_address) like cast(:pattern as text)
               or lower(u.display_name) like cast(:pattern as text)
            order by case when cast(:sort as text) = 'recent' then g.last_played end desc nulls last,
                     coalesce(g.total, 0) desc,
                     u.id
            limit :size offset :offset
            """, nativeQuery = true)
    List<Object[]> playerPage(@Param("pattern") String pattern,
                              @Param("sort") String sort,
                              @Param("size") int size,
                              @Param("offset") int offset);

    /** How many accounts the same filter matches, for the page count under the table. */
    @Query(value = """
            select count(*) from users u
            where cast(:pattern as text) is null
               or lower(u.wallet_address) like cast(:pattern as text)
               or lower(u.display_name) like cast(:pattern as text)
            """, nativeQuery = true)
    long countPlayers(@Param("pattern") String pattern);
}

package com.example.candles.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

import com.example.candles.entity.DemoAccount;

public interface DemoAccountRepository extends JpaRepository<DemoAccount, Long> {

    /**
     * The account row, locked for the duration of the transaction.
     *
     * Balances are derived, so there is no balance column to update atomically — which means
     * nothing stops two concurrent buys from both reading the same cash, both finding it
     * sufficient, and both inserting. This lock is what serialises a player's own trades against
     * each other. It is per-account, so it costs nothing to anyone else.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from DemoAccount a where a.userId = :userId")
    Optional<DemoAccount> findForUpdate(@Param("userId") Long userId);

    /**
     * One page of paper accounts with their owners and their trade counts, as rows of
     * [userId, wallet, displayName, openedAt, resets, trades, lastTradeAt].
     *
     * Busiest first, because an admin opening this page is looking for whoever is actually
     * using the feature. An account with no trades is still listed: opening the terminal
     * creates the row, so "has a demo account and has never traded" is a real and separate
     * thing from not having one.
     *
     * Every count is filtered to the account's own {@code opened_at}, so the figures here mean
     * what the player's terminal means. The rows a reset left behind are counted separately,
     * on the detail view, where there is room to explain them.
     */
    @Query(value = """
            select d.user_id, u.wallet_address, u.display_name, d.opened_at, d.resets,
                   count(t.id) filter (where t.created_at >= d.opened_at),
                   max(t.created_at) filter (where t.created_at >= d.opened_at)
            from demo_accounts d
            join users u on u.id = d.user_id
            left join demo_trades t on t.user_id = d.user_id
            group by d.user_id, u.wallet_address, u.display_name, d.opened_at, d.resets
            order by count(t.id) filter (where t.created_at >= d.opened_at) desc, d.user_id
            limit :size offset :offset
            """, nativeQuery = true)
    List<Object[]> accountPage(@Param("size") int size, @Param("offset") int offset);

    /** Total resets across every account — the counter V17 kept in case this is ever revisited. */
    @Query("select coalesce(sum(a.resets), 0) from DemoAccount a")
    long totalResets();
}

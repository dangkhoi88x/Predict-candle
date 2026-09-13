package com.example.candles.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

import com.example.candles.entity.DemoTrade;

public interface DemoTradeRepository extends JpaRepository<DemoTrade, Long> {

    /**
     * One account's trades since it was last opened, oldest first — the order the fold needs.
     *
     * {@code since} is what makes a reset work without deleting anything: the rows before the
     * mark stay on disk and simply stop being read.
     */
    @Query("""
            select t from DemoTrade t join fetch t.asset
            where t.userId = :userId and t.createdAt >= :since
            order by t.createdAt, t.id
            """)
    List<DemoTrade> findSince(@Param("userId") Long userId, @Param("since") Instant since);

    /**
     * The whole feature in one row, for the admin pane's header:
     * [trades, tradesToday, tradesWeek, fees, tradingAccounts].
     *
     * {@code fees} is the only figure here that is not a count, and it is the one worth having:
     * the fee is what stops a paper account from being a coin-flip machine, so how much of it
     * has actually been charged says whether anyone is trading enough for it to matter.
     */
    @Query(value = """
            select count(*),
                   count(*) filter (where created_at >= :today),
                   count(*) filter (where created_at >= :weekAgo),
                   coalesce(sum(fee), 0),
                   count(distinct user_id)
            from demo_trades
            """, nativeQuery = true)
    Object[] tradeSummary(@Param("today") Instant today, @Param("weekAgo") Instant weekAgo);

    /**
     * Every fill belonging to one page of accounts, each since its own {@code opened_at}, as
     * rows of [userId, symbol, side, quantity, price, fee].
     *
     * One query for the page rather than a fold per account. {@link #findSince} answers about
     * one account and takes its mark as a parameter; here the mark is different for every row,
     * so the join to {@code demo_accounts} supplies it — which is also what keeps a reset
     * meaning the same thing on this page as it does in the player's own terminal.
     *
     * Ordered by user then by time, because {@link com.example.candles.domain.DemoPortfolio#of}
     * folds a sequence and the sequence is what makes cost basis average out correctly.
     */
    @Query(value = """
            select t.user_id, a.symbol, t.side, t.quantity, t.price, t.fee
            from demo_trades t
            join demo_accounts d on d.user_id = t.user_id
            join assets a on a.id = t.asset_id
            where t.user_id in :userIds and t.created_at >= d.opened_at
            order by t.user_id, t.created_at, t.id
            """, nativeQuery = true)
    List<Object[]> fillsForAccounts(@Param("userIds") List<Long> userIds);

    /**
     * How many rows an account has from before its current {@code opened_at} — trades a reset
     * left on disk and the fold no longer reads.
     *
     * Nobody else can see this number, and it is the one that explains an account holding two
     * hundred rows while its terminal shows three trades. A reset deletes nothing, so without
     * it the row count and the visible history disagree with no way to find out why.
     */
    @Query(value = """
            select count(*) from demo_trades t
            join demo_accounts d on d.user_id = t.user_id
            where t.user_id = :userId and t.created_at < d.opened_at
            """, nativeQuery = true)
    long countBeforeReset(@Param("userId") Long userId);

    /** One account's trades since its last reset, newest first — the detail view's list. */
    @Query("""
            select t from DemoTrade t join fetch t.asset
            where t.userId = :userId and t.createdAt >= :since
            order by t.createdAt desc, t.id desc
            """)
    List<DemoTrade> findRecentSince(@Param("userId") Long userId, @Param("since") Instant since,
                                     Pageable pageable);
}

package com.example.candles.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Paper trading, seen from the outside.
 *
 * The feature had no admin surface at all: an immutable trade log, a table that holds no money,
 * and nothing anywhere that said how much of it was being used. Every figure here is folded out
 * of those same rows on read — there is still no balance stored, and this page does not
 * introduce one.
 */
public record AdminDemoOverview(Summary summary, List<Account> accounts,
                                 int page, int size, long total, boolean hasMore) {

    /**
     * {@code accounts} counts rows in {@code demo_accounts} and {@code tradingAccounts} counts
     * the ones that have ever traded. They come apart on purpose: opening the terminal creates
     * the row, so the gap between them is how many people looked at paper trading and did not
     * place a trade — which is the question this header exists to answer.
     *
     * {@code fees} is the only figure that is not a count. The fee is what stops a paper account
     * from being a coin-flip machine, so how much of it has been charged says whether anyone is
     * trading enough for it to bite. {@code startingBalance} and {@code feeBps} travel with it
     * so the page reads the configuration in force rather than a number typed into the markup.
     */
    public record Summary(long accounts, long tradingAccounts, long trades,
                           long tradesToday, long tradesWeek, BigDecimal fees, long resets,
                           BigDecimal startingBalance, int feeBps) {
    }

    /**
     * One paper account.
     *
     * {@code cash} and {@code realisedPnl} need no prices and are always exact. The other three
     * need every held position to have a live price, and are null together when any of them does
     * not — a whole-account figure that quietly dropped a holding would be a worse answer than
     * a gap, and this is a list somebody is comparing accounts across.
     */
    public record Account(Long userId, String walletAddress, String displayName,
                           Instant openedAt, int resets, long trades, Instant lastTradeAt,
                           int positions, BigDecimal cash, BigDecimal realisedPnl,
                           BigDecimal holdingsValue, BigDecimal unrealisedPnl, BigDecimal equity) {
    }
}

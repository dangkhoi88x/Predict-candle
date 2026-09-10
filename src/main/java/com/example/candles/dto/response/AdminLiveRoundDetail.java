package com.example.candles.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One live round with the names behind its pool split.
 *
 * This is the same round {@code /api/live/history/{n}} already replays for players, with one
 * deliberate difference: the calls here carry the wallet address and the account id. The public
 * roster never does — a display name defaults to a shortened wallet precisely so a page nobody
 * had to sign in to open cannot be scraped for addresses — but an admin looking at a round
 * because something went wrong on it needs to be able to find the account it went wrong for.
 */
public record AdminLiveRoundDetail(long number, String asset, String timeframe,
                                    Instant openTime, Instant closeAt, boolean settled,
                                    BigDecimal open, BigDecimal close, String result,
                                    List<Call> calls) {

    /**
     * {@code correct} is null while the round is unsettled, which is the one place a null is
     * the honest answer: the candle that decides this call does not exist yet, so the player is
     * neither right nor wrong.
     */
    public record Call(Long userId, String walletAddress, String displayName,
                        String direction, Instant createdAt, Boolean correct) {
    }
}

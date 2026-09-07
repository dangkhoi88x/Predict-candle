package com.example.candles.dto.request;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

/**
 * One paper trade.
 *
 * A buy says how much cash to spend and a sell says how much of the holding to release, because
 * that is how each side is actually decided — nobody buys "0.0731 BTC", they buy $500 of it, and
 * nobody sells "$500 of it", they sell some or all of what they hold.
 *
 * There is deliberately no price field. The price is whatever the server reads at the moment of
 * the trade; accepting one from the client would let a browser name the price it filled at.
 */
public record DemoTradeRequest(
        @NotBlank String asset,
        @NotBlank String side,
        BigDecimal amountUsd,
        BigDecimal quantity
) {
}

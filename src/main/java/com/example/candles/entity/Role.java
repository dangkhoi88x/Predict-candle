package com.example.candles.entity;

/**
 * What an account is allowed to do. Two levels on purpose: everyone who connects a wallet is a
 * USER, and a short, deliberately-maintained list of accounts is ADMIN.
 *
 * Adding a third level later means deciding what it may do that ADMIN may not, or vice versa —
 * until there is a real answer to that, more levels would only be decoration.
 */
public enum Role {
    USER,
    ADMIN
}

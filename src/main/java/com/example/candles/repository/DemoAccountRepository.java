package com.example.candles.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}

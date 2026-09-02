package com.example.candles.repository;

import com.example.candles.domain.Role;
import com.example.candles.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

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
}

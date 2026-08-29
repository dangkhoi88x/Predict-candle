package com.example.candles.repository;

import com.example.candles.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByWalletAddress(String walletAddress);
}

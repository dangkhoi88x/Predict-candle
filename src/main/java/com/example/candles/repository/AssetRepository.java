package com.example.candles.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

import com.example.candles.entity.Asset;

public interface AssetRepository extends JpaRepository<Asset, Long> {

    List<Asset> findByEnabledTrueOrderByPositionAscSymbolAsc();

    List<Asset> findAllByOrderByPositionAscSymbolAsc();

    Optional<Asset> findBySymbol(String symbol);
}

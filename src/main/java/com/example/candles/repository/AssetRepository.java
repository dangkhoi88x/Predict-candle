package com.example.candles.repository;

import com.example.candles.domain.Asset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AssetRepository extends JpaRepository<Asset, Long> {

    List<Asset> findByEnabledTrueOrderByPositionAscSymbolAsc();

    List<Asset> findAllByOrderByPositionAscSymbolAsc();


    Optional<Asset> findBySymbol(String symbol);
}

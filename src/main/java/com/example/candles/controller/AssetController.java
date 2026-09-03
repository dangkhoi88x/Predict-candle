package com.example.candles.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

import com.example.candles.entity.Asset;
import com.example.candles.repository.AssetRepository;

/**
 * The pairs on offer. Public, because the game's asset picker is built from it — a list that
 * only an admin could read would mean the picker stayed hard-coded, and then adding a pair
 * from the admin screen would create a row nobody could play.
 */
@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private final AssetRepository assetRepository;

    public AssetController(AssetRepository assetRepository) {
        this.assetRepository = assetRepository;
    }

    @GetMapping
    public List<AssetDto> enabled() {
        return assetRepository.findByEnabledTrueOrderByPositionAscSymbolAsc().stream().map(AssetDto::from).toList();
    }

    public record AssetDto(String symbol, String shortSymbol, String name, String type) {

        public static AssetDto from(Asset asset) {
            return new AssetDto(asset.getSymbol(), asset.shortSymbol(), asset.getName(),
                    asset.getType().name());
        }
    }
}

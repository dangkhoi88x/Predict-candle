package com.example.candles.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.Duration;
import java.util.List;

import com.example.candles.entity.Asset;
import com.example.candles.repository.AssetRepository;

/**
 * The pairs on offer. Public, because the game's asset picker is built from it — a list that
 * only an admin could read would mean the picker stayed hard-coded, and then adding a pair
 * from the admin screen would create a row nobody could play.
 *
 * Cached five minutes and served stale for an hour after that, the same bargain as
 * {@link ContentController} with a shorter tail: a pair switched off should leave the picker
 * within the hour, not the day.
 */
@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private final AssetRepository assetRepository;

    public AssetController(AssetRepository assetRepository) {
        this.assetRepository = assetRepository;
    }

    static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()
            .staleWhileRevalidate(Duration.ofHours(1));

    @GetMapping
    public ResponseEntity<List<AssetDto>> enabled() {
        return ResponseEntity.ok().cacheControl(CACHE).body(
                assetRepository.findByEnabledTrueOrderByPositionAscSymbolAsc().stream().map(AssetDto::from).toList());
    }

    public record AssetDto(String symbol, String shortSymbol, String name, String type) {

        public static AssetDto from(Asset asset) {
            return new AssetDto(asset.getSymbol(), asset.shortSymbol(), asset.getName(),
                    asset.getType().name());
        }
    }
}

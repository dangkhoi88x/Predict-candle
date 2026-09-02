package com.example.candles.admin;

import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.Asset;
import com.example.candles.domain.AssetType;
import com.example.candles.ingestion.CandleSyncService;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.CandleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * Adding and switching off trading pairs.
 *
 * A new pair arrives with no candles, so it is created disabled and backfilled before anyone
 * can be handed a chart for it — {@code RoundSelectionService} would otherwise fail on a pair
 * with nothing to select from. Enabling is the separate, deliberate step once the history is
 * there, which the admin list shows.
 */
@Service
public class AdminAssetService {

    private static final Logger log = LoggerFactory.getLogger(AdminAssetService.class);

    private final AssetRepository assetRepository;
    private final CandleRepository candleRepository;
    private final CandleSyncService candleSyncService;
    private final CandlesProperties properties;

    public AdminAssetService(AssetRepository assetRepository, CandleRepository candleRepository,
                             CandleSyncService candleSyncService, CandlesProperties properties) {
        this.assetRepository = assetRepository;
        this.candleRepository = candleRepository;
        this.candleSyncService = candleSyncService;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public List<AssetSummary> assets() {
        return assetRepository.findAllByOrderByPositionAscSymbolAsc().stream().map(this::summarise).toList();
    }

    @Transactional
    public AssetSummary create(String rawSymbol, String name) {
        String symbol = rawSymbol == null ? "" : rawSymbol.trim().toUpperCase(Locale.ROOT);
        if (!symbol.matches("[A-Z0-9]{5,20}")) {
            throw new IllegalArgumentException("Mã cặp không hợp lệ — ví dụ đúng: ADAUSDT.");
        }
        if (assetRepository.findBySymbol(symbol).isPresent()) {
            throw new IllegalArgumentException("Cặp " + symbol + " đã có rồi.");
        }
        String label = name == null || name.isBlank() ? symbol : name.trim();

        Asset asset = new Asset(symbol, label, AssetType.CRYPTO);
        // Created switched off: it has no history yet, and a pair in the picker with no
        // candles is a round request that cannot be answered.
        asset.setEnabled(false);
        // At the end of the list, not the front: a pair nobody has backfilled yet should not
        // displace the one players actually come for.
        asset.setPosition(assetRepository.findAll().stream()
                .mapToInt(Asset::getPosition).max().orElse(-1) + 1);
        assetRepository.save(asset);
        log.info("Added asset {} ({}), disabled until it has been backfilled", symbol, label);
        return summarise(asset);
    }

    /**
     * Swaps a pair with its neighbour in the current order. Expressed as a swap rather than a
     * new absolute position so two rows can never be handed the same one, which is how a list
     * reordered a few times starts flickering between orders on reload.
     */
    @Transactional
    public List<AssetSummary> move(Long id, boolean up) {
        List<Asset> ordered = assetRepository.findAllByOrderByPositionAscSymbolAsc();
        int index = -1;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).getId().equals(id)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw new IllegalArgumentException("Không tìm thấy asset #" + id);
        }
        int target = up ? index - 1 : index + 1;
        if (target < 0 || target >= ordered.size()) {
            return assets();
        }

        // Rewritten from the visible order, because positions seeded by hand leave ties and a
        // straight swap of two equal numbers changes nothing.
        Asset moved = ordered.remove(index);
        ordered.add(target, moved);
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setPosition(i);
        }
        assetRepository.saveAll(ordered);
        return ordered.stream().map(this::summarise).toList();
    }

    @Transactional
    public AssetSummary setEnabled(Long id, boolean enabled) {
        Asset asset = require(id);
        if (enabled && candleRepository.countByAssetAndTimeframe(asset, properties.timeframe()) == 0) {
            throw new IllegalArgumentException(
                    "Chưa có nến nào cho " + asset.getSymbol() + " — chạy Sync trước khi bật.");
        }
        asset.setEnabled(enabled);
        assetRepository.save(asset);
        return summarise(asset);
    }

    /** Fetches history for a pair that has none yet. Long-running on the first call. */
    @Transactional
    public AssetSummary backfill(Long id) {
        Asset asset = require(id);
        candleSyncService.sync(asset);
        return summarise(asset);
    }

    private Asset require(Long id) {
        return assetRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy asset #" + id));
    }

    private AssetSummary summarise(Asset asset) {
        return new AssetSummary(asset.getId(), asset.getSymbol(), asset.shortSymbol(),
                asset.getName(), asset.getType().name(), asset.isEnabled(), asset.getPosition(),
                candleRepository.countByAssetAndTimeframe(asset, properties.timeframe()));
    }

    public record AssetSummary(Long id, String symbol, String shortSymbol, String name,
                                String type, boolean enabled, int position, long candles) {
    }
}

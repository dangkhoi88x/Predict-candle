package com.example.candles.pattern;

import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.Asset;
import com.example.candles.domain.Candle;
import com.example.candles.repository.AssetRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Scans an asset's full candle history for real occurrences of a library pattern, so "Tìm ví
 * dụ thật" can show a genuine chart snippet instead of the hand-drawn illustration.
 */
@Service
public class PatternExampleService {

    private static final int CONTEXT_BEFORE = 10;
    private static final int CONTEXT_AFTER = 15;
    private static final int MAX_MATCHES_COLLECTED = 500;

    private final AssetRepository assetRepository;
    private final CandlesProperties properties;
    private final CandleHistoryLoader historyLoader;

    public PatternExampleService(AssetRepository assetRepository,
                                  CandlesProperties properties,
                                  CandleHistoryLoader historyLoader) {
        this.assetRepository = assetRepository;
        this.properties = properties;
        this.historyLoader = historyLoader;
    }

    public PatternExample findExample(String patternId, String assetSymbol) {
        PatternDefinition definition = PatternLibrary.get(patternId);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown pattern: " + patternId);
        }

        Asset asset = assetRepository.findBySymbol(assetSymbol.toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Unknown asset: " + assetSymbol));
        String timeframe = properties.timeframe();
        List<Candle> history = historyLoader.load(asset, timeframe);

        int windowSize = definition.windowSize();
        List<Integer> matchEnds = new ArrayList<>();
        for (int end = windowSize; end <= history.size(); end++) {
            if (definition.matcher().matches(history.subList(end - windowSize, end))) {
                matchEnds.add(end);
                if (matchEnds.size() >= MAX_MATCHES_COLLECTED) {
                    break;
                }
            }
        }

        if (matchEnds.isEmpty()) {
            throw new IllegalStateException("Chưa tìm thấy ví dụ thực tế nào cho mẫu này trên " + asset.getSymbol());
        }

        int chosenEnd = matchEnds.get(ThreadLocalRandom.current().nextInt(matchEnds.size()));
        int patternStart = chosenEnd - windowSize;
        int sliceFrom = Math.max(0, patternStart - CONTEXT_BEFORE);
        int sliceTo = Math.min(history.size(), chosenEnd + CONTEXT_AFTER);
        List<Candle> slice = history.subList(sliceFrom, sliceTo);
        Candle lastPatternCandle = history.get(chosenEnd - 1);

        return new PatternExample(
                asset.getSymbol(),
                timeframe,
                lastPatternCandle.getOpenTime(),
                slice,
                patternStart - sliceFrom,
                windowSize
        );
    }
}

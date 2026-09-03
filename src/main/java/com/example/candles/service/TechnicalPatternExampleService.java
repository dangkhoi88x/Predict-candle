package com.example.candles.service;

import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import com.example.candles.config.CandlesProperties;
import com.example.candles.entity.Asset;
import com.example.candles.entity.Candle;
import com.example.candles.pattern.CandleHistoryLoader;
import com.example.candles.pattern.PatternExample;
import com.example.candles.pattern.SwingPivotDetector;
import com.example.candles.pattern.SwingPoint;
import com.example.candles.pattern.TechnicalPatternDefinition;
import com.example.candles.pattern.TechnicalPatternLibrary;
import com.example.candles.repository.AssetRepository;

/**
 * Scans an asset's full candle history for real occurrences of a chart-level technical
 * pattern (Head & Shoulders, triangles, flags…), matched against swing points rather than a
 * fixed candle window — see {@link SwingPivotDetector} and {@link TechnicalPatternLibrary}.
 */
@Service
public class TechnicalPatternExampleService {

    private static final int CONTEXT_BEFORE = 10;
    private static final int CONTEXT_AFTER = 10;
    private static final int MAX_MATCHES_COLLECTED = 300;
    private static final double ZIGZAG_DEVIATION = 0.015;

    private final AssetRepository assetRepository;
    private final CandlesProperties properties;
    private final CandleHistoryLoader historyLoader;

    public TechnicalPatternExampleService(AssetRepository assetRepository,
                                           CandlesProperties properties,
                                           CandleHistoryLoader historyLoader) {
        this.assetRepository = assetRepository;
        this.properties = properties;
        this.historyLoader = historyLoader;
    }

    public PatternExample findExample(String patternId, String assetSymbol) {
        TechnicalPatternDefinition definition = TechnicalPatternLibrary.get(patternId);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown technical pattern: " + patternId);
        }

        Asset asset = assetRepository.findBySymbol(assetSymbol.toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Unknown asset: " + assetSymbol));
        String timeframe = properties.timeframe();
        List<Candle> history = historyLoader.load(asset, timeframe);
        List<SwingPoint> pivots = SwingPivotDetector.detect(history, ZIGZAG_DEVIATION);

        List<int[]> matches = new ArrayList<>();
        for (int i = 0; i < pivots.size(); i++) {
            Optional<int[]> match = definition.matcher().match(history, pivots, i);
            if (match.isPresent()) {
                matches.add(match.get());
                if (matches.size() >= MAX_MATCHES_COLLECTED) {
                    break;
                }
            }
        }

        if (matches.isEmpty()) {
            throw new IllegalStateException("Chưa tìm thấy ví dụ thực tế nào cho mẫu này trên " + asset.getSymbol());
        }

        int[] chosen = matches.get(ThreadLocalRandom.current().nextInt(matches.size()));
        int patternStart = chosen[0];
        int patternEnd = chosen[1];
        int sliceFrom = Math.max(0, patternStart - CONTEXT_BEFORE);
        int sliceTo = Math.min(history.size(), patternEnd + CONTEXT_AFTER);
        List<Candle> slice = history.subList(sliceFrom, sliceTo);
        Candle lastPatternCandle = history.get(patternEnd - 1);

        return new PatternExample(
                asset.getSymbol(),
                timeframe,
                lastPatternCandle.getOpenTime(),
                slice,
                patternStart - sliceFrom,
                patternEnd - patternStart
        );
    }
}

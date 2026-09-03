package com.example.candles.pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.List;

import com.example.candles.entity.ContentItem;
import com.example.candles.entity.ContentKind;
import com.example.candles.repository.ContentItemRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seam between editable content and code.
 *
 * Every pattern card carries a "Tìm ví dụ thật" button that asks the server to scan history
 * for that pattern, and the server finds the scanner by the card's key. A card whose key has
 * no matcher is a button that always fails; a matcher with no card is a pattern nobody can
 * reach. Neither shows up on a page that renders fine, which is why it is asserted here.
 *
 * Lives in the pattern package because TechnicalPatternLibrary is package-private.
 */
@SpringBootTest
class ContentKeysMatchMatchersTest {

    @Autowired
    private ContentItemRepository repository;

    @Test
    void everyCandlestickCardHasAMatcherAndEveryMatcherHasACard() {
        List<String> cardKeys = keysOf(ContentKind.CANDLE_PATTERN);

        assertThat(cardKeys).isNotEmpty()
                .allSatisfy(key -> assertThat(PatternLibrary.get(key))
                        .as("no matcher registered for candlestick pattern '%s'", key)
                        .isNotNull());
        assertThat(cardKeys).containsExactlyInAnyOrderElementsOf(PatternLibrary.all().keySet());
    }

    @Test
    void everyChartPatternCardHasAMatcher() {
        List<String> cardKeys = keysOf(ContentKind.TECHNICAL_PATTERN);

        assertThat(cardKeys).isNotEmpty()
                .allSatisfy(key -> assertThat(TechnicalPatternLibrary.get(key))
                        .as("no matcher registered for chart pattern '%s'", key)
                        .isNotNull());
    }

    private List<String> keysOf(ContentKind kind) {
        return repository.findByKindOrderByPositionAscIdAsc(kind).stream()
                .map(ContentItem::getItemKey)
                .toList();
    }
}

package com.example.candles.entity;

/**
 * The three sets of editorial content on the site.
 *
 * {@code boundToCode} is the important distinction. A candlestick or chart pattern's key is
 * how the entry finds its matcher in {@code PatternLibrary} / {@code TechnicalPatternLibrary},
 * which are Java — so those entries can have their wording edited but cannot be created,
 * deleted or re-keyed from an admin screen without leaving a card whose "Tìm ví dụ thật"
 * button asks the server to scan for something no code can detect. Psychology notes have
 * nothing behind them and are ordinary rows.
 */
public enum ContentKind {

    CANDLE_PATTERN(true),
    TECHNICAL_PATTERN(true),
    PSYCHOLOGY(false);

    private final boolean boundToCode;

    ContentKind(boolean boundToCode) {
        this.boundToCode = boundToCode;
    }

    public boolean boundToCode() {
        return boundToCode;
    }

    public static ContentKind parse(String raw) {
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Loại nội dung không hợp lệ: " + raw);
        }
    }
}

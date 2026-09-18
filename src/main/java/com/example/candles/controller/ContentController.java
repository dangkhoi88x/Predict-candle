package com.example.candles.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.Duration;
import java.util.List;

import com.example.candles.dto.response.ContentItemDto;
import com.example.candles.entity.ContentKind;
import com.example.candles.service.ContentService;

/**
 * Public reading side for the pattern, chart-pattern and psychology tabs.
 *
 * Cached five minutes, then served stale for up to a day while the browser refreshes it behind
 * the page. {@code patterns.js} asks for the candlestick library on every load, because the game
 * names a pattern mid-round from it, and on the demo's 0.1 CPU that request took up to 1.5s beside
 * the page's others. An admin's edit reaches a returning visitor on their next load but one,
 * which is the price: the library changes a few times a year.
 */
@RestController
@RequestMapping("/api/content")
public class ContentController {

    private final ContentService contentService;

    public ContentController(ContentService contentService) {
        this.contentService = contentService;
    }

    static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()
            .staleWhileRevalidate(Duration.ofDays(1));

    @GetMapping("/{kind}")
    public ResponseEntity<List<ContentItemDto>> byKind(@PathVariable String kind) {
        return ResponseEntity.ok().cacheControl(CACHE).body(contentService.published(ContentKind.parse(kind)));
    }
}

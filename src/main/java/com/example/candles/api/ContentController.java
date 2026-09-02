package com.example.candles.api;

import com.example.candles.content.ContentItemDto;
import com.example.candles.content.ContentKind;
import com.example.candles.content.ContentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Public reading side for the pattern, chart-pattern and psychology tabs. */
@RestController
@RequestMapping("/api/content")
public class ContentController {

    private final ContentService contentService;

    public ContentController(ContentService contentService) {
        this.contentService = contentService;
    }

    @GetMapping("/{kind}")
    public List<ContentItemDto> byKind(@PathVariable String kind) {
        return contentService.published(ContentKind.parse(kind));
    }
}

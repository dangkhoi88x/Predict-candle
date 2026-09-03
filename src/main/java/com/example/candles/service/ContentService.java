package com.example.candles.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

import com.example.candles.dto.ContentItemDto;
import com.example.candles.dto.ContentItemRequest;
import com.example.candles.entity.ContentItem;
import com.example.candles.entity.ContentKind;
import com.example.candles.repository.ContentItemRepository;

/**
 * Reads and writes the pattern, chart-pattern and psychology entries, and enforces the one
 * rule that separates them: entries whose key names a Java matcher may have their wording
 * edited, but may not be created, deleted, or given a different key.
 */
@Service
public class ContentService {

    private final ContentItemRepository repository;
    private final ObjectMapper objectMapper;

    public ContentService(ContentItemRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<ContentItemDto> published(ContentKind kind) {
        return repository.findByKindAndPublishedTrueOrderByPositionAscIdAsc(kind)
                .stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<ContentItemDto> all(ContentKind kind) {
        return repository.findByKindOrderByPositionAscIdAsc(kind).stream().map(this::toDto).toList();
    }

    @Transactional
    public ContentItemDto create(ContentKind kind, ContentItemRequest request) {
        if (kind.boundToCode()) {
            throw new IllegalArgumentException(
                    "Không thể thêm mục mới cho loại này: mỗi mục phải có một matcher tương ứng trong mã nguồn.");
        }
        String key = uniqueKey(kind, chosenKey(request), null);
        ContentItem item = new ContentItem(kind, key);
        apply(item, request);
        return toDto(repository.save(item));
    }

    @Transactional
    public ContentItemDto update(Long id, ContentItemRequest request) {
        ContentItem item = require(id);
        if (!item.getKind().boundToCode()) {
            item.setItemKey(uniqueKey(item.getKind(), chosenKey(request), item.getItemKey()));
        } else if (request.itemKey() != null && !request.itemKey().isBlank()
                && !request.itemKey().equals(item.getItemKey())) {
            // Refused rather than ignored: silently keeping the old key would leave the editor
            // believing a rename happened.
            throw new IllegalArgumentException(
                    "Không đổi được khoá của mục này — nó là liên kết tới matcher trong mã nguồn.");
        }
        apply(item, request);
        item.touch();
        return toDto(repository.save(item));
    }

    @Transactional
    public void delete(Long id) {
        ContentItem item = require(id);
        if (item.getKind().boundToCode()) {
            throw new IllegalArgumentException(
                    "Không xoá được mục này: matcher trong mã nguồn vẫn tồn tại và thẻ sẽ mất phần mô tả.");
        }
        repository.delete(item);
    }

    private ContentItem require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy mục #" + id));
    }

    private void apply(ContentItem item, ContentItemRequest request) {
        item.setTitle(request.title().trim());
        item.setBody(request.body() == null || request.body().isNull()
                ? "{}" : objectMapper.writeValueAsString(request.body()));
        item.setPosition(request.position() == null ? 0 : request.position());
        item.setPublished(request.published());
    }

    private ContentItemDto toDto(ContentItem item) {
        JsonNode body = objectMapper.readTree(
                item.getBody() == null || item.getBody().isBlank() ? "{}" : item.getBody());
        return new ContentItemDto(item.getId(), item.getKind().name(), item.getItemKey(),
                item.getTitle(), body, item.getPosition(), item.isPublished(),
                !item.getKind().boundToCode(), item.getCreatedAt(), item.getUpdatedAt());
    }

    private String chosenKey(ContentItemRequest request) {
        String key = request.itemKey();
        return key != null && !key.isBlank() ? slugify(key) : slugify(request.title());
    }

    private String uniqueKey(ContentKind kind, String base, String keeping) {
        String candidate = base.isBlank() ? "muc" : base;
        for (int suffix = 2; ; suffix++) {
            if (candidate.equals(keeping) || !repository.existsByKindAndItemKey(kind, candidate)) {
                return candidate;
            }
            candidate = base + "-" + suffix;
        }
    }

    static String slugify(String text) {
        String folded = Normalizer.normalize(text.replace("đ", "d").replace("Đ", "D"), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return folded.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }
}

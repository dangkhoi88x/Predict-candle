package com.example.candles.content;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContentItemRepository extends JpaRepository<ContentItem, Long> {

    List<ContentItem> findByKindAndPublishedTrueOrderByPositionAscIdAsc(ContentKind kind);

    List<ContentItem> findByKindOrderByPositionAscIdAsc(ContentKind kind);

    Optional<ContentItem> findByKindAndItemKey(ContentKind kind, String itemKey);

    boolean existsByKindAndItemKey(ContentKind kind, String itemKey);
}

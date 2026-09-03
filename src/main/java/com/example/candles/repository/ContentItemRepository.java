package com.example.candles.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

import com.example.candles.entity.ContentItem;
import com.example.candles.entity.ContentKind;

public interface ContentItemRepository extends JpaRepository<ContentItem, Long> {

    List<ContentItem> findByKindAndPublishedTrueOrderByPositionAscIdAsc(ContentKind kind);

    List<ContentItem> findByKindOrderByPositionAscIdAsc(ContentKind kind);

    Optional<ContentItem> findByKindAndItemKey(ContentKind kind, String itemKey);

    boolean existsByKindAndItemKey(ContentKind kind, String itemKey);
}

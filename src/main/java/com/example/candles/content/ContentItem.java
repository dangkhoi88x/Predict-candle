package com.example.candles.content;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * One card in the pattern library, the chart-pattern library or the psychology tab.
 *
 * {@code body} holds the entry exactly as the front end consumes it, so the renderers that
 * already existed did not have to learn a new shape. Held as JSON text for the same reason
 * as {@link com.example.candles.blog.BlogPost} — the conversion happens at the API boundary
 * with the application's own mapper rather than by whichever JSON library Hibernate finds.
 */
@Entity
@Table(
        name = "content_items",
        uniqueConstraints = @UniqueConstraint(name = "uq_content_items_kind_key",
                columnNames = {"kind", "item_key"}),
        indexes = @Index(name = "idx_content_items_kind_published",
                columnList = "kind, published, position")
)
public class ContentItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ContentKind kind;

    /** For the pattern kinds this is the key a Java matcher is registered under. */
    @Column(name = "item_key", nullable = false, length = 80)
    private String itemKey;

    @Column(nullable = false, length = 300)
    private String title;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String body = "{}";

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private boolean published = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ContentItem() {
    }

    public ContentItem(ContentKind kind, String itemKey) {
        this.kind = kind;
        this.itemKey = itemKey;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() {
        return id;
    }

    public ContentKind getKind() {
        return kind;
    }

    public String getItemKey() {
        return itemKey;
    }

    public void setItemKey(String itemKey) {
        this.itemKey = itemKey;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public boolean isPublished() {
        return published;
    }

    public void setPublished(boolean published) {
        this.published = published;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}

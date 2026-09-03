package com.example.candles.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * A post on the "Blog / Kiến Thức" tab.
 *
 * {@code tags} and {@code body} are held as raw JSON strings against jsonb columns rather than
 * mapped object graphs. Hibernate can map a List or a record into jsonb, but it serializes
 * with whichever JSON library it finds, and this project runs Jackson 3 (tools.jackson) while
 * Jackson 2 is also on the classpath via a transitive dependency — leaving that choice to
 * autodetection is how a subtle serialization difference gets into stored content. The API
 * layer converts, explicitly, with the application's own mapper.
 */
@Entity
@Table(
        name = "blog_posts",
        uniqueConstraints = @UniqueConstraint(name = "uq_blog_posts_slug", columnNames = "slug"),
        indexes = @Index(name = "idx_blog_posts_published_position", columnList = "published, position")
)
public class BlogPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 180)
    private String slug;

    @Column(nullable = false, length = 300)
    private String title;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String tags = "[]";

    @Column(length = 300)
    private String source;

    @Column(name = "source_url", length = 600)
    private String sourceUrl;

    @Column(name = "image_credit")
    private String imageCredit;

    /** Inline SVG drawn for this post; null when the post uses an author's image instead. */
    @Column(name = "cover_svg")
    private String coverSvg;

    @Column(name = "cover_img", length = 600)
    private String coverImg;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String body = "[]";

    @Column(nullable = false)
    private boolean published;

    /** Display order on the tab, low to high. */
    @Column(nullable = false)
    private int position;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BlogPost() {
    }

    public BlogPost(String slug) {
        this.slug = slug;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getImageCredit() {
        return imageCredit;
    }

    public void setImageCredit(String imageCredit) {
        this.imageCredit = imageCredit;
    }

    public String getCoverSvg() {
        return coverSvg;
    }

    public void setCoverSvg(String coverSvg) {
        this.coverSvg = coverSvg;
    }

    public String getCoverImg() {
        return coverImg;
    }

    public void setCoverImg(String coverImg) {
        this.coverImg = coverImg;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public boolean isPublished() {
        return published;
    }

    public void setPublished(boolean published) {
        this.published = published;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
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

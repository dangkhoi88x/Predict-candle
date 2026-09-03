package com.example.candles.blog;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Reads and writes blog posts, and owns the one conversion this feature needs: jsonb columns
 * are held on the entity as JSON text, and the API speaks real JSON.
 */
@Service
public class BlogService {

    private static final String EMPTY_ARRAY = "[]";

    private final BlogPostRepository repository;
    private final ObjectMapper objectMapper;

    public BlogService(BlogPostRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<BlogPostDto> published() {
        return repository.findByPublishedTrueOrderByPositionAscIdAsc().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<BlogPostDto> all() {
        return repository.findAllByOrderByPositionAscIdAsc().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public BlogPostDto byId(Long id) {
        return toDto(require(id));
    }

    @Transactional
    public BlogPostDto create(BlogPostRequest request) {
        String slug = uniqueSlug(chosenSlug(request), null);
        BlogPost post = new BlogPost(slug);
        apply(post, request);
        return toDto(repository.save(post));
    }

    @Transactional
    public BlogPostDto update(Long id, BlogPostRequest request) {
        BlogPost post = require(id);
        post.setSlug(uniqueSlug(chosenSlug(request), post.getSlug()));
        apply(post, request);
        post.touch();
        return toDto(repository.save(post));
    }

    @Transactional
    public void delete(Long id) {
        repository.delete(require(id));
    }

    private BlogPost require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bài viết #" + id));
    }

    private void apply(BlogPost post, BlogPostRequest request) {
        post.setTitle(request.title().trim());
        post.setTags(json(request.tags()));
        post.setSource(blankToNull(request.source()));
        post.setSourceUrl(blankToNull(request.sourceUrl()));
        post.setImageCredit(blankToNull(request.imageCredit()));
        post.setCoverSvg(blankToNull(request.coverSvg()));
        post.setCoverImg(blankToNull(request.coverImg()));
        post.setBody(json(request.body()));
        post.setPublished(request.published());
        post.setPosition(request.position() == null ? 0 : request.position());
    }

    private BlogPostDto toDto(BlogPost post) {
        return new BlogPostDto(
                post.getId(), post.getSlug(), post.getTitle(), parse(post.getTags()),
                post.getSource(), post.getSourceUrl(), post.getImageCredit(),
                post.getCoverSvg(), post.getCoverImg(), parse(post.getBody()),
                post.isPublished(), post.getPosition(), post.getCreatedAt(), post.getUpdatedAt());
    }

    private JsonNode parse(String raw) {
        return objectMapper.readTree(raw == null || raw.isBlank() ? EMPTY_ARRAY : raw);
    }

    private String json(JsonNode node) {
        return node == null || node.isNull() ? EMPTY_ARRAY : objectMapper.writeValueAsString(node);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String chosenSlug(BlogPostRequest request) {
        String slug = blankToNull(request.slug());
        return slug != null ? slugify(slug) : slugify(request.title());
    }

    /**
     * Keeps the slug unique without making the editor deal with it: a clash gets a numeric
     * suffix. {@code keeping} is the post's current slug, which must not count as a clash
     * with itself when saving an edit that did not change the title.
     */
    private String uniqueSlug(String base, String keeping) {
        String candidate = base.isBlank() ? "bai-viet" : base;
        for (int suffix = 2; ; suffix++) {
            if (candidate.equals(keeping) || !repository.existsBySlug(candidate)) {
                return candidate;
            }
            candidate = base + "-" + suffix;
        }
    }

    /** Vietnamese titles, ASCII slugs: strip the diacritics, keep the letters. */
    static String slugify(String text) {
        String folded = Normalizer.normalize(text.replace("đ", "d").replace("Đ", "D"), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        String slug = folded.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.length() > 120 ? slug.substring(0, 120).replaceAll("-+$", "") : slug;
    }
}

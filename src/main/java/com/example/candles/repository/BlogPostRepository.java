package com.example.candles.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

import com.example.candles.entity.BlogPost;

public interface BlogPostRepository extends JpaRepository<BlogPost, Long> {

    /** What the public tab shows: published only, in display order. */
    List<BlogPost> findByPublishedTrueOrderByPositionAscIdAsc();

    /** The admin list, drafts included. */
    List<BlogPost> findAllByOrderByPositionAscIdAsc();

    Optional<BlogPost> findBySlug(String slug);

    boolean existsBySlug(String slug);
}

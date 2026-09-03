package com.example.candles.api;

import com.example.candles.blog.BlogPostDto;
import com.example.candles.blog.BlogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The public reading side: published posts, in display order. No auth — this is the tab. */
@RestController
@RequestMapping("/api/blog")
public class BlogController {

    private final BlogService blogService;

    public BlogController(BlogService blogService) {
        this.blogService = blogService;
    }

    @GetMapping("/posts")
    public List<BlogPostDto> posts() {
        return blogService.published();
    }
}

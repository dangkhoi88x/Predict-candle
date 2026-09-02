package com.example.candles.api;

import com.example.candles.auth.AdminAccess;
import com.example.candles.blog.BlogPostDto;
import com.example.candles.blog.BlogPostRequest;
import com.example.candles.blog.BlogService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Blog CRUD. Under /api/admin, so SecurityConfig already requires ROLE_ADMIN; every method
 * that writes also calls AdminAccess, which re-reads the role from the database rather than
 * trusting the caller's token.
 *
 * The listing includes drafts — that is the difference between this and the public endpoint.
 */
@RestController
@RequestMapping("/api/admin/blog/posts")
public class AdminBlogController {

    private final BlogService blogService;
    private final AdminAccess adminAccess;

    public AdminBlogController(BlogService blogService, AdminAccess adminAccess) {
        this.blogService = blogService;
        this.adminAccess = adminAccess;
    }

    @GetMapping
    public List<BlogPostDto> list() {
        return blogService.all();
    }

    @GetMapping("/{id}")
    public BlogPostDto get(@PathVariable Long id) {
        return blogService.byId(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BlogPostDto create(@Valid @RequestBody BlogPostRequest request) {
        adminAccess.requireAdmin();
        return blogService.create(request);
    }

    @PutMapping("/{id}")
    public BlogPostDto update(@PathVariable Long id, @Valid @RequestBody BlogPostRequest request) {
        adminAccess.requireAdmin();
        return blogService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        adminAccess.requireAdmin();
        blogService.delete(id);
    }
}

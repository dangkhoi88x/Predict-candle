package com.example.candles.controller;

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

import com.example.candles.dto.request.ContentItemRequest;
import com.example.candles.dto.response.ContentItemDto;
import com.example.candles.entity.ContentKind;
import com.example.candles.security.AdminAccess;
import com.example.candles.service.ContentService;

/**
 * Editing for the three content libraries. Create and delete only succeed for kinds that are
 * not bound to a matcher in the code — ContentService decides that, not this controller, so
 * the rule holds however the endpoint is reached.
 */
@RestController
@RequestMapping("/api/admin/content")
public class AdminContentController {

    private final ContentService contentService;
    private final AdminAccess adminAccess;

    public AdminContentController(ContentService contentService, AdminAccess adminAccess) {
        this.contentService = contentService;
        this.adminAccess = adminAccess;
    }

    @GetMapping("/{kind}")
    public List<ContentItemDto> list(@PathVariable String kind) {
        return contentService.all(ContentKind.parse(kind));
    }

    @PostMapping("/{kind}")
    @ResponseStatus(HttpStatus.CREATED)
    public ContentItemDto create(@PathVariable String kind,
                                 @Valid @RequestBody ContentItemRequest request) {
        adminAccess.requireAdmin();
        return contentService.create(ContentKind.parse(kind), request);
    }

    @PutMapping("/{id}")
    public ContentItemDto update(@PathVariable Long id, @Valid @RequestBody ContentItemRequest request) {
        adminAccess.requireAdmin();
        return contentService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        adminAccess.requireAdmin();
        contentService.delete(id);
    }
}

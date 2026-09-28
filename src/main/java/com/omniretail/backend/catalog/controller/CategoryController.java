package com.omniretail.backend.catalog.controller;

import com.omniretail.backend.catalog.dto.CategoryCreateRequest;
import com.omniretail.backend.catalog.dto.CategoryResponse;
import com.omniretail.backend.catalog.dto.CategoryUpdateRequest;
import com.omniretail.backend.catalog.entity.CategoryStatus;
import com.omniretail.backend.catalog.service.CategoryService;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/catalog/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    @RequirePermission("catalog.categories.read")
    public List<CategoryResponse> list(
            @RequestParam(required = false) CategoryStatus status) {
        return categoryService.list(status);
    }

    @GetMapping("/{id}")
    @RequirePermission("catalog.categories.read")
    public CategoryResponse getById(@PathVariable UUID id) {
        return categoryService.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("catalog.categories.manage")
    public CategoryResponse create(@Valid @RequestBody CategoryCreateRequest request) {
        return categoryService.create(request);
    }

    @PutMapping("/{id}")
    @RequirePermission("catalog.categories.manage")
    public CategoryResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody CategoryUpdateRequest request) {
        return categoryService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission("catalog.categories.manage")
    public void archive(@PathVariable UUID id) {
        categoryService.archive(id);
    }
}

package com.omniretail.backend.catalog.service;

import com.omniretail.backend.catalog.dto.CategoryCreateRequest;
import com.omniretail.backend.catalog.dto.CategoryResponse;
import com.omniretail.backend.catalog.dto.CategoryUpdateRequest;
import com.omniretail.backend.catalog.entity.Category;
import com.omniretail.backend.catalog.entity.CategoryStatus;
import com.omniretail.backend.catalog.repository.CategoryRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.media.MediaStorageService;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryService {

    private static final String CATEGORY_NOT_FOUND = "Categoría no encontrada.";
    private static final String CATEGORY_PARENT_NOT_FOUND =
            "Categoría padre no encontrada o inactiva.";
    private static final String CATEGORY_CYCLE =
            "La jerarquía de categorías no puede contener ciclos.";

    private final CategoryRepository categoryRepository;
    private final MediaStorageService mediaStorageService;
    private final CurrentUser currentUser;

    public List<CategoryResponse> list(CategoryStatus status) {
        UUID tenantId = currentUser.require().tenantId();
        List<Category> categories = status == null
                ? categoryRepository.findByTenantIdOrderByNameAsc(tenantId)
                : categoryRepository.findByTenantIdAndStatusOrderByNameAsc(
                        tenantId, status);
        return categories.stream().map(CategoryResponse::from).toList();
    }

    public CategoryResponse getById(UUID id) {
        UUID tenantId = currentUser.require().tenantId();
        return CategoryResponse.from(requireCategory(tenantId, id));
    }

    @Transactional
    public CategoryResponse create(CategoryCreateRequest request) {
        UUID tenantId = currentUser.require().tenantId();
        requireActiveParent(tenantId, request.parentId());

        String name = request.name().trim();
        String slug = normalizeSlug(
                request.slug() == null || request.slug().isBlank()
                        ? name
                        : request.slug());
        validateSlugAvailable(tenantId, slug);

        Category category = Category.builder()
                .parentId(request.parentId())
                .name(name)
                .slug(slug)
                .description(request.description())
                .imageUrl(request.imageUrl())
                .status(request.status() != null ? request.status() : CategoryStatus.active)
                .build();
        category.setTenantId(tenantId);
        return CategoryResponse.from(categoryRepository.saveAndFlush(category));
    }

    @Transactional
    public CategoryResponse update(UUID id, CategoryUpdateRequest request) {
        UUID tenantId = currentUser.require().tenantId();
        Category category = requireCategory(tenantId, id);
        validateHierarchy(tenantId, category.getId(), request.parentId());

        if (category.getStatus() != CategoryStatus.archived
                && request.status() == CategoryStatus.archived) {
            ensureHasNoActiveChildren(tenantId, category.getId());
        }

        String slug = category.getSlug();
        if (request.slug() != null && !request.slug().isBlank()) {
            slug = normalizeSlug(request.slug());
            if (categoryRepository.existsByTenantIdAndSlugAndIdNot(
                    tenantId, slug, category.getId())) {
                throw BusinessException.conflict(
                        "CATEGORY_SLUG_CONFLICT",
                        "Ya existe una categoría con ese slug.");
            }
        }

        category.setParentId(request.parentId());
        category.setName(request.name().trim());
        category.setSlug(slug);
        category.setDescription(request.description());
        category.setImageUrl(request.imageUrl());
        category.setStatus(request.status());
        return CategoryResponse.from(categoryRepository.saveAndFlush(category));
    }

    @Transactional
    public void archive(UUID id) {
        UUID tenantId = currentUser.require().tenantId();
        Category category = requireCategory(tenantId, id);
        if (category.getStatus() == CategoryStatus.archived) {
            return;
        }
        ensureHasNoActiveChildren(tenantId, category.getId());
        category.setStatus(CategoryStatus.archived);
        categoryRepository.saveAndFlush(category);
    }

    @Transactional
    public CategoryResponse uploadImage(UUID id, MultipartFile file) {
        UUID tenantId = currentUser.require().tenantId();
        Category category = requireCategoryForUpdate(tenantId, id);
        String newUrl = mediaStorageService.storeImage(tenantId, "categories", id, file);
        String previous = category.getImageUrl();
        try {
            category.setImageUrl(newUrl);
            Category saved = categoryRepository.saveAndFlush(category);
            mediaStorageService.deleteAfterCommit(previous);
            return CategoryResponse.from(saved);
        } catch (RuntimeException exception) {
            mediaStorageService.deleteQuietly(newUrl);
            throw exception;
        }
    }

    @Transactional
    public CategoryResponse deleteImage(UUID id) {
        UUID tenantId = currentUser.require().tenantId();
        Category category = requireCategoryForUpdate(tenantId, id);
        String previous = category.getImageUrl();
        category.setImageUrl(null);
        Category saved = categoryRepository.saveAndFlush(category);
        mediaStorageService.deleteAfterCommit(previous);
        return CategoryResponse.from(saved);
    }

    private Category requireCategory(UUID tenantId, UUID id) {
        return categoryRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "CATEGORY_NOT_FOUND",
                        CATEGORY_NOT_FOUND));
    }

    private Category requireCategoryForUpdate(UUID tenantId, UUID id) {
        return categoryRepository.findForUpdateByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", "Categoria no encontrada."));
    }

    private Category requireActiveParent(UUID tenantId, UUID parentId) {
        if (parentId == null) {
            return null;
        }
        Category parent = categoryRepository.findByTenantIdAndId(tenantId, parentId)
                .orElseThrow(CategoryService::parentNotFound);
        if (parent.getStatus() != CategoryStatus.active) {
            throw parentNotFound();
        }
        return parent;
    }

    private void validateHierarchy(UUID tenantId, UUID categoryId, UUID parentId) {
        if (parentId == null) {
            return;
        }
        if (parentId.equals(categoryId)) {
            throw categoryCycle();
        }

        Category ancestor = requireActiveParent(tenantId, parentId);
        Set<UUID> visited = new HashSet<>();
        while (ancestor != null) {
            if (ancestor.getId().equals(categoryId) || !visited.add(ancestor.getId())) {
                throw categoryCycle();
            }
            UUID ancestorParentId = ancestor.getParentId();
            ancestor = ancestorParentId == null
                    ? null
                    : categoryRepository.findByTenantIdAndId(tenantId, ancestorParentId)
                            .orElseThrow(CategoryService::parentNotFound);
        }
    }

    private void validateSlugAvailable(UUID tenantId, String slug) {
        if (categoryRepository.existsByTenantIdAndSlug(tenantId, slug)) {
            throw BusinessException.conflict(
                    "CATEGORY_SLUG_CONFLICT",
                    "Ya existe una categoría con ese slug.");
        }
    }

    private void ensureHasNoActiveChildren(UUID tenantId, UUID categoryId) {
        if (categoryRepository.existsByTenantIdAndParentIdAndStatus(
                tenantId, categoryId, CategoryStatus.active)) {
            throw BusinessException.conflict(
                    "CATEGORY_HAS_ACTIVE_CHILDREN",
                    "No se puede archivar una categoría que tiene categorías hijas activas.");
        }
    }

    private static String normalizeSlug(String value) {
        String slug = Normalizer.normalize(
                value.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[^\\p{L}\\p{N}]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isEmpty() || slug.length() > 200) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "CATEGORY_SLUG_INVALID",
                    "El slug de la categoría no es válido.");
        }
        return slug;
    }

    private static BusinessException parentNotFound() {
        return new BusinessException(
                HttpStatus.NOT_FOUND,
                "CATEGORY_PARENT_NOT_FOUND",
                CATEGORY_PARENT_NOT_FOUND);
    }

    private static BusinessException categoryCycle() {
        return BusinessException.conflict("CATEGORY_CYCLE", CATEGORY_CYCLE);
    }
}

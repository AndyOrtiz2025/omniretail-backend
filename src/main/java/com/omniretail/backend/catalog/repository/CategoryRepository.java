package com.omniretail.backend.catalog.repository;

import com.omniretail.backend.catalog.entity.Category;
import com.omniretail.backend.catalog.entity.CategoryStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    Optional<Category> findByTenantIdAndId(UUID tenantId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select category from Category category where category.tenantId = :tenantId and category.id = :id")
    Optional<Category> findForUpdateByTenantIdAndId(
            @Param("tenantId") UUID tenantId, @Param("id") UUID id);

    Optional<Category> findByTenantIdAndSlug(UUID tenantId, String slug);

    List<Category> findByTenantIdAndStatus(UUID tenantId, CategoryStatus status);

    List<Category> findByTenantId(UUID tenantId);

    List<Category> findByTenantIdOrderByNameAsc(UUID tenantId);

    List<Category> findByTenantIdAndStatusOrderByNameAsc(
            UUID tenantId, CategoryStatus status);

    boolean existsByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByIdAndTenantIdAndStatus(UUID id, UUID tenantId, CategoryStatus status);

    boolean existsByTenantIdAndSlug(UUID tenantId, String slug);

    boolean existsByTenantIdAndSlugAndIdNot(UUID tenantId, String slug, UUID id);

    boolean existsByTenantIdAndParentIdAndStatus(
            UUID tenantId, UUID parentId, CategoryStatus status);
}

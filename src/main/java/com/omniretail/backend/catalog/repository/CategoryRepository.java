package com.omniretail.backend.catalog.repository;

import com.omniretail.backend.catalog.entity.Category;
import com.omniretail.backend.catalog.entity.CategoryStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    Optional<Category> findByTenantIdAndSlug(UUID tenantId, String slug);

    List<Category> findByTenantIdAndStatus(UUID tenantId, CategoryStatus status);

    List<Category> findByTenantId(UUID tenantId);

    boolean existsByTenantIdAndSlug(UUID tenantId, String slug);
}

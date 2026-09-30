package com.omniretail.backend.catalog.repository;

import com.omniretail.backend.catalog.entity.ProductPriceHistory;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductPriceHistoryRepository extends JpaRepository<ProductPriceHistory, UUID> {

    Page<ProductPriceHistory> findByTenantIdAndProductIdOrderByCreatedAtDesc(
            UUID tenantId, UUID productId, Pageable pageable);
}

package com.omniretail.backend.catalog.repository;

import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    Page<Product> findByTenantId(UUID tenantId, Pageable pageable);

    Optional<Product> findByTenantIdAndSku(UUID tenantId, String sku);

    Optional<Product> findByTenantIdAndBarcode(UUID tenantId, String barcode);

    List<Product> findByTenantIdAndStatus(UUID tenantId, ProductStatus status);

    List<Product> findByTenantIdAndStatusAndChannelEcommerceTrue(UUID tenantId, ProductStatus status);

    Optional<Product> findByTenantIdAndIdAndStatusAndChannelEcommerceTrue(
            UUID tenantId, UUID id, ProductStatus status);

    List<Product> findByTenantIdAndCategoryId(UUID tenantId, UUID categoryId);

    boolean existsByTenantIdAndSku(UUID tenantId, String sku);
}

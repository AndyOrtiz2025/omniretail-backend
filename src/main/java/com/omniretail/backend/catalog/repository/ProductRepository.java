package com.omniretail.backend.catalog.repository;

import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    Page<Product> findByTenantId(UUID tenantId, Pageable pageable);

    List<Product> findByTenantId(UUID tenantId);

    List<Product> findByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);

    Optional<Product> findByTenantIdAndId(UUID tenantId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select product from Product product where product.tenantId = :tenantId and product.id = :id")
    Optional<Product> findForUpdateByTenantIdAndId(
            @Param("tenantId") UUID tenantId, @Param("id") UUID id);

    Optional<Product> findByTenantIdAndSku(UUID tenantId, String sku);

    Optional<Product> findByTenantIdAndBarcode(UUID tenantId, String barcode);

    boolean existsByTenantIdAndBarcode(UUID tenantId, String barcode);

    boolean existsByTenantIdAndBarcodeAndIdNot(UUID tenantId, String barcode, UUID id);

    List<Product> findByTenantIdAndStatus(UUID tenantId, ProductStatus status);

    List<Product> findByTenantIdAndStatusAndTrackingStockTrue(UUID tenantId, ProductStatus status);

    List<Product> findByTenantIdAndStatusAndChannelEcommerceTrue(UUID tenantId, ProductStatus status);

    Optional<Product> findByTenantIdAndIdAndStatusAndChannelEcommerceTrue(
            UUID tenantId, UUID id, ProductStatus status);

    List<Product> findByTenantIdAndCategoryId(UUID tenantId, UUID categoryId);

    boolean existsByTenantIdAndSku(UUID tenantId, String sku);

    boolean existsByTenantIdAndSkuAndIdNot(UUID tenantId, String sku, UUID id);
}

package com.omniretail.backend.pos.repository;

import com.omniretail.backend.pos.entity.SaleItem;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SaleItemRepository extends JpaRepository<SaleItem, UUID> {

    @Query(
            value = """
                    SELECT EXISTS (
                        SELECT 1
                        FROM sale_items item
                        JOIN sales sale ON sale.id = item.sale_id
                        WHERE sale.tenant_id = :tenantId
                          AND item.product_id = :productId
                    )
                    """,
            nativeQuery = true)
    boolean existsByTenantIdAndProductId(
            @Param("tenantId") UUID tenantId, @Param("productId") UUID productId);

    @Query("""
            SELECT item
            FROM SaleItem item, Sale sale
            WHERE item.saleId = sale.id
              AND sale.tenantId = :tenantId
              AND item.saleId = :saleId
            """)
    List<SaleItem> findByTenantIdAndSaleId(@Param("tenantId") UUID tenantId, @Param("saleId") UUID saleId);

    @Query("""
            SELECT item
            FROM SaleItem item, Sale sale
            WHERE item.saleId = sale.id
              AND sale.tenantId = :tenantId
              AND sale.status <> com.omniretail.backend.pos.entity.SaleStatus.cancelled
              AND sale.customerId IS NOT NULL
            """)
    List<SaleItem> findActiveItemsByTenantId(@Param("tenantId") UUID tenantId);

    @Query("""
            SELECT item
            FROM SaleItem item, Sale sale
            WHERE item.saleId = sale.id
              AND sale.tenantId = :tenantId
              AND sale.status <> com.omniretail.backend.pos.entity.SaleStatus.cancelled
              AND sale.customerId = :customerId
            """)
    List<SaleItem> findActiveItemsByTenantIdAndCustomerId(
            @Param("tenantId") UUID tenantId, @Param("customerId") UUID customerId);

    List<SaleItem> findBySaleIdIn(Collection<UUID> saleIds);
}

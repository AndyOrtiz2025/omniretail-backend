package com.omniretail.backend.pos.repository;

import com.omniretail.backend.pos.entity.SaleItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SaleItemRepository extends JpaRepository<SaleItem, UUID> {

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
}

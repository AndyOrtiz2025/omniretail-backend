package com.omniretail.backend.pos.repository;

import com.omniretail.backend.pos.entity.SaleItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SaleItemRepository extends JpaRepository<SaleItem, UUID> {

    @Query("""
            SELECT item
            FROM SaleItem item, Sale sale
            WHERE item.saleId = sale.id
              AND sale.tenantId = :tenantId
              AND item.saleId = :saleId
            """)
    List<SaleItem> findByTenantIdAndSaleId(UUID tenantId, UUID saleId);
}

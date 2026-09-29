package com.omniretail.backend.pos.repository;

import com.omniretail.backend.pos.entity.SaleReturnItem;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SaleReturnItemRepository extends JpaRepository<SaleReturnItem, UUID> {

    @Query("select coalesce(sum(i.quantity), 0) from SaleReturnItem i "
            + "where i.tenantId = :tenantId and i.saleItemId = :saleItemId")
    BigDecimal sumReturned(@Param("tenantId") UUID tenantId, @Param("saleItemId") UUID saleItemId);

    List<SaleReturnItem> findByTenantIdAndReturnId(UUID tenantId, UUID returnId);
}

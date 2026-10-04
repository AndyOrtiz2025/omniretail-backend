package com.omniretail.backend.purchasing.repository;

import com.omniretail.backend.purchasing.entity.ReceiptIncident;
import com.omniretail.backend.purchasing.entity.ReceiptIncidentStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReceiptIncidentRepository extends JpaRepository<ReceiptIncident, UUID> {

    Optional<ReceiptIncident> findByTenantIdAndId(UUID tenantId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select incident from ReceiptIncident incident where incident.tenantId = :tenantId and incident.id = :id")
    Optional<ReceiptIncident> findForUpdateByTenantIdAndId(
            @Param("tenantId") UUID tenantId, @Param("id") UUID id);

    Page<ReceiptIncident> findByTenantIdAndGoodsReceiptId(
            UUID tenantId, UUID goodsReceiptId, Pageable pageable);

    boolean existsByTenantIdAndGoodsReceiptItemId(UUID tenantId, UUID goodsReceiptItemId);

    boolean existsByTenantIdAndGoodsReceiptIdAndStatus(
            UUID tenantId, UUID goodsReceiptId, ReceiptIncidentStatus status);
}

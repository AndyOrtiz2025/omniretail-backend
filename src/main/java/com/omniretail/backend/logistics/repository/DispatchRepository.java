package com.omniretail.backend.logistics.repository;

import com.omniretail.backend.logistics.entity.*;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface DispatchRepository extends JpaRepository<Dispatch, UUID> {
    List<Dispatch> findByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);
    Optional<Dispatch> findByTenantIdAndBranchIdAndId(UUID tenantId, UUID branchId, UUID id);
    Optional<Dispatch> findByTenantIdAndBranchIdAndSourceTypeAndSourceId(UUID tenantId, UUID branchId, DispatchSourceType sourceType, UUID sourceId);
    Optional<Dispatch> findByTenantIdAndOrderId(UUID tenantId, UUID orderId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Dispatch d where d.tenantId=:tenantId and d.branchId=:branchId and d.sourceType=:sourceType and d.sourceId=:sourceId")
    Optional<Dispatch> findBySourceForUpdate(@Param("tenantId") UUID tenantId, @Param("branchId") UUID branchId, @Param("sourceType") DispatchSourceType sourceType, @Param("sourceId") UUID sourceId);
}

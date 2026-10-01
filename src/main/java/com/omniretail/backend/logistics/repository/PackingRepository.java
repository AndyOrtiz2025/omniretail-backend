package com.omniretail.backend.logistics.repository;

import com.omniretail.backend.logistics.entity.Packing;
import com.omniretail.backend.logistics.entity.PackingSourceType;
import com.omniretail.backend.logistics.entity.PackingStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PackingRepository extends JpaRepository<Packing, UUID> {

    Optional<Packing> findByTenantIdAndBranchIdAndId(
            UUID tenantId, UUID branchId, UUID id);

    Optional<Packing> findByTenantIdAndBranchIdAndSourceTypeAndSourceId(
            UUID tenantId, UUID branchId, PackingSourceType sourceType, UUID sourceId);

    Optional<Packing> findByTenantIdAndBranchIdAndPickingOrderId(
            UUID tenantId, UUID branchId, UUID pickingOrderId);

    List<Packing> findByTenantIdAndBranchIdAndStatusOrderByCreatedAtAsc(
            UUID tenantId, UUID branchId, PackingStatus status);
}

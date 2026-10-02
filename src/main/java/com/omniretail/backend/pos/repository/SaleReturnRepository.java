package com.omniretail.backend.pos.repository;

import com.omniretail.backend.pos.entity.SaleReturn;
import java.util.UUID;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaleReturnRepository extends JpaRepository<SaleReturn, UUID> {

    List<SaleReturn> findByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);

    Page<SaleReturn> findByTenantIdAndBranchId(UUID tenantId, UUID branchId, Pageable pageable);
}

package com.omniretail.backend.pos.repository;

import com.omniretail.backend.pos.entity.Sale;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaleRepository extends JpaRepository<Sale, UUID> {

    Optional<Sale> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<Sale> findByTenantIdAndNumber(UUID tenantId, String number);

    Optional<Sale> findByTenantIdAndConfirmationId(UUID tenantId, UUID confirmationId);

    List<Sale> findByTenantIdAndBranchIdOrderByCreatedAtDescIdDesc(UUID tenantId, UUID branchId);
}

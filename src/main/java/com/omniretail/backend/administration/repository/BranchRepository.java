package com.omniretail.backend.administration.repository;

import com.omniretail.backend.administration.entity.Branch;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchRepository extends JpaRepository<Branch, UUID> {

    Optional<Branch> findByTenantIdAndCode(UUID tenantId, String code);

    List<Branch> findByTenantId(UUID tenantId);

    boolean existsByTenantIdAndCode(UUID tenantId, String code);
}

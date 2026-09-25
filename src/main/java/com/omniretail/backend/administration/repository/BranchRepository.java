package com.omniretail.backend.administration.repository;

import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.BranchStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchRepository extends JpaRepository<Branch, UUID> {

    Optional<Branch> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<Branch> findByTenantIdAndCode(UUID tenantId, String code);

    Page<Branch> findByTenantId(UUID tenantId, Pageable pageable);

    Page<Branch> findByTenantIdAndStatus(UUID tenantId, BranchStatus status, Pageable pageable);

    List<Branch> findByTenantId(UUID tenantId);

    List<Branch> findByTenantIdAndStatus(UUID tenantId, BranchStatus status);

    boolean existsByTenantIdAndCodeIgnoreCase(UUID tenantId, String code);

    boolean existsByTenantIdAndCodeIgnoreCaseAndIdNot(UUID tenantId, String code, UUID id);
}

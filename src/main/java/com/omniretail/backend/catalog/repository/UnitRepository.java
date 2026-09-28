package com.omniretail.backend.catalog.repository;

import com.omniretail.backend.catalog.entity.Unit;
import com.omniretail.backend.catalog.entity.UnitStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UnitRepository extends JpaRepository<Unit, UUID> {

    Optional<Unit> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<Unit> findByTenantIdAndCode(UUID tenantId, String code);

    List<Unit> findByTenantIdAndStatus(UUID tenantId, UnitStatus status);

    List<Unit> findByTenantId(UUID tenantId);

    Page<Unit> findByTenantId(UUID tenantId, Pageable pageable);

    Page<Unit> findByTenantIdAndStatus(
            UUID tenantId, UnitStatus status, Pageable pageable);

    boolean existsByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByIdAndTenantIdAndStatus(UUID id, UUID tenantId, UnitStatus status);

    boolean existsByTenantIdAndCode(UUID tenantId, String code);
}

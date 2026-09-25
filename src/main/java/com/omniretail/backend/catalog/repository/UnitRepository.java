package com.omniretail.backend.catalog.repository;

import com.omniretail.backend.catalog.entity.Unit;
import com.omniretail.backend.catalog.entity.UnitStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UnitRepository extends JpaRepository<Unit, UUID> {

    Optional<Unit> findByTenantIdAndCode(UUID tenantId, String code);

    List<Unit> findByTenantIdAndStatus(UUID tenantId, UnitStatus status);

    List<Unit> findByTenantId(UUID tenantId);

    boolean existsByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByTenantIdAndCode(UUID tenantId, String code);
}

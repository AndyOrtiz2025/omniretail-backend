package com.omniretail.backend.catalog.repository;

import com.omniretail.backend.catalog.entity.AttributeDefinition;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttributeDefinitionRepository extends JpaRepository<AttributeDefinition, UUID> {

    Page<AttributeDefinition> findByTenantId(UUID tenantId, Pageable pageable);

    Optional<AttributeDefinition> findByTenantIdAndId(UUID tenantId, UUID id);

    List<AttributeDefinition> findByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);

    boolean existsByTenantIdAndCodeIgnoreCase(UUID tenantId, String code);
}

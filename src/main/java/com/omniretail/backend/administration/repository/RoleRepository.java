package com.omniretail.backend.administration.repository;

import com.omniretail.backend.administration.entity.Role;
import com.omniretail.backend.administration.entity.RoleStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<Role> findByTenantIdAndName(UUID tenantId, String name);

    Page<Role> findByTenantId(UUID tenantId, Pageable pageable);

    Page<Role> findByTenantIdAndStatus(UUID tenantId, RoleStatus status, Pageable pageable);

    List<Role> findByTenantId(UUID tenantId);

    List<Role> findByTenantIdAndStatus(UUID tenantId, RoleStatus status);

    boolean existsByTenantIdAndNameIgnoreCase(UUID tenantId, String name);

    boolean existsByTenantIdAndNameIgnoreCaseAndIdNot(UUID tenantId, String name, UUID id);
}

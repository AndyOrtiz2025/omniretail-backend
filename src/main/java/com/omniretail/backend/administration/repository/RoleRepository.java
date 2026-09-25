package com.omniretail.backend.administration.repository;

import com.omniretail.backend.administration.entity.Role;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByTenantIdAndName(UUID tenantId, String name);

    List<Role> findByTenantId(UUID tenantId);
}

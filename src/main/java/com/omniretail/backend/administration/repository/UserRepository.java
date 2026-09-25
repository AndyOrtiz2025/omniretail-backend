package com.omniretail.backend.administration.repository;

import com.omniretail.backend.administration.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByTenantIdAndEmail(UUID tenantId, String email);

    Optional<User> findByTenantIdAndId(UUID tenantId, UUID id);

    boolean existsByTenantIdAndEmail(UUID tenantId, String email);
}

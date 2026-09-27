package com.omniretail.backend.administration.repository;

import com.omniretail.backend.administration.entity.EcommerceConfig;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EcommerceConfigRepository extends JpaRepository<EcommerceConfig, UUID> {

    Optional<EcommerceConfig> findByTenantId(UUID tenantId);
}

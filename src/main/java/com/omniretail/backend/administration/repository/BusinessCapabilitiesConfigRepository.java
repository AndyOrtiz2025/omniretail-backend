package com.omniretail.backend.administration.repository;

import com.omniretail.backend.administration.entity.BusinessCapabilitiesConfig;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessCapabilitiesConfigRepository extends JpaRepository<BusinessCapabilitiesConfig, UUID> {

    Optional<BusinessCapabilitiesConfig> findByTenantId(UUID tenantId);
}

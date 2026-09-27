package com.omniretail.backend.administration.repository;

import com.omniretail.backend.administration.entity.HeroBannerConfig;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HeroBannerConfigRepository extends JpaRepository<HeroBannerConfig, UUID> {

    Optional<HeroBannerConfig> findByTenantId(UUID tenantId);
}

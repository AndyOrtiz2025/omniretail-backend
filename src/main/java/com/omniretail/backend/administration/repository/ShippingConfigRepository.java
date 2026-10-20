package com.omniretail.backend.administration.repository;

import com.omniretail.backend.administration.entity.ShippingChannel;
import com.omniretail.backend.administration.entity.ShippingConfig;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShippingConfigRepository extends JpaRepository<ShippingConfig, UUID> {

    List<ShippingConfig> findByTenantId(UUID tenantId);

    Optional<ShippingConfig> findByTenantIdAndChannel(UUID tenantId, ShippingChannel channel);
}

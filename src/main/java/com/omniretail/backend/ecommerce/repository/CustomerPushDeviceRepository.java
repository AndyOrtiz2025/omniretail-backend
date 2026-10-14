package com.omniretail.backend.ecommerce.repository;

import com.omniretail.backend.ecommerce.entity.CustomerPushDevice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerPushDeviceRepository
        extends JpaRepository<CustomerPushDevice, UUID> {

    Optional<CustomerPushDevice> findByTenantIdAndPushToken(
            UUID tenantId,
            String pushToken
    );

    List<CustomerPushDevice> findByTenantIdAndCustomerIdAndEnabledTrue(
            UUID tenantId,
            UUID customerId
    );
}

package com.omniretail.backend.shared.notification;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantEmailSenderConfigRepository extends JpaRepository<TenantEmailSenderConfig, UUID> {

    Optional<TenantEmailSenderConfig> findByTenantId(UUID tenantId);
}

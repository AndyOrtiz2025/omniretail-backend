package com.omniretail.backend.administration.repository;

import com.omniretail.backend.administration.entity.SubscriptionInvoice;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionInvoiceRepository extends JpaRepository<SubscriptionInvoice, UUID> {
    Optional<SubscriptionInvoice> findByTenantIdAndSubscriptionIdAndCycleStart(
            UUID tenantId, UUID subscriptionId, Instant cycleStart);
    List<SubscriptionInvoice> findByTenantIdAndSubscriptionIdOrderByCycleStartDesc(
            UUID tenantId, UUID subscriptionId);
}

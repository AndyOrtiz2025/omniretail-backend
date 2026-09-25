package com.omniretail.backend.pos.repository;

import com.omniretail.backend.pos.entity.Payment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByTenantIdAndId(UUID tenantId, UUID id);

    List<Payment> findByTenantIdAndSaleIdOrderByCreatedAtAscIdAsc(UUID tenantId, UUID saleId);

    List<Payment> findByTenantIdAndOrderIdOrderByCreatedAtAscIdAsc(UUID tenantId, UUID orderId);
}

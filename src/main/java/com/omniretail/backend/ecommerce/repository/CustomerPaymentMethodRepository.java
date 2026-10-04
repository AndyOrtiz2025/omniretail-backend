package com.omniretail.backend.ecommerce.repository;

import com.omniretail.backend.ecommerce.entity.CustomerPaymentMethod;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Toda consulta va acotada por tenant y cliente: un id de otro cliente se comporta como inexistente. */
public interface CustomerPaymentMethodRepository extends JpaRepository<CustomerPaymentMethod, UUID> {

    List<CustomerPaymentMethod> findByTenantIdAndCustomerIdOrderByCreatedAtAscIdAsc(UUID tenantId, UUID customerId);

    Optional<CustomerPaymentMethod> findByTenantIdAndCustomerIdAndId(UUID tenantId, UUID customerId, UUID id);

    boolean existsByTenantIdAndCustomerId(UUID tenantId, UUID customerId);
}

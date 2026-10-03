package com.omniretail.backend.ecommerce.repository;

import com.omniretail.backend.ecommerce.entity.Address;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Toda consulta va acotada por tenant y cliente: un id de otro cliente se comporta como inexistente. */
public interface AddressRepository extends JpaRepository<Address, UUID> {

    List<Address> findByTenantIdAndCustomerIdOrderByCreatedAtAscIdAsc(UUID tenantId, UUID customerId);

    Optional<Address> findByTenantIdAndCustomerIdAndId(UUID tenantId, UUID customerId, UUID id);

    boolean existsByTenantIdAndCustomerId(UUID tenantId, UUID customerId);
}

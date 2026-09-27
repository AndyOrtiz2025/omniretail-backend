package com.omniretail.backend.ecommerce.repository;

import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.entity.CustomerStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    List<Customer> findByTenantId(UUID tenantId);

    List<Customer> findByTenantIdAndStatus(UUID tenantId, CustomerStatus status);

    Optional<Customer> findByTenantIdAndId(UUID tenantId, UUID id);
}

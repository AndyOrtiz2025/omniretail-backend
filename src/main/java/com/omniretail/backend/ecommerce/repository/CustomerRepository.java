package com.omniretail.backend.ecommerce.repository;

import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.entity.CustomerStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    List<Customer> findByTenantId(UUID tenantId);

    List<Customer> findByTenantIdAndStatus(UUID tenantId, CustomerStatus status);

    Page<Customer> findByTenantId(UUID tenantId, Pageable pageable);

    Page<Customer> findByTenantIdAndStatus(UUID tenantId, CustomerStatus status, Pageable pageable);

    Optional<Customer> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<Customer> findByTenantIdAndCode(UUID tenantId, String code);

    Optional<Customer> findByTenantIdAndEmail(UUID tenantId, String email);
}

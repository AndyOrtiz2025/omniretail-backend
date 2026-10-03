package com.omniretail.backend.ecommerce.repository;

import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.entity.CustomerStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    List<Customer> findByTenantId(UUID tenantId);

    List<Customer> findByTenantIdAndStatus(UUID tenantId, CustomerStatus status);

    Optional<Customer> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<Customer> findByTenantIdAndUserIdAndStatus(
            UUID tenantId, UUID userId, CustomerStatus status);

    /** SELECT ... FOR UPDATE: serializa los cambios de direcciones (y su predeterminada) del cliente. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.tenantId = :tenantId and c.id = :id")
    Optional<Customer> findByTenantIdAndIdForUpdate(UUID tenantId, UUID id);
}

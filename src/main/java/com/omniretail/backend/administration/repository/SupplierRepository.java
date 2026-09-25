package com.omniretail.backend.administration.repository;

import com.omniretail.backend.administration.entity.Supplier;
import com.omniretail.backend.administration.entity.SupplierStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierRepository extends JpaRepository<Supplier, UUID> {

    Optional<Supplier> findByTenantIdAndId(UUID tenantId, UUID id);

    Page<Supplier> findByTenantId(UUID tenantId, Pageable pageable);

    Page<Supplier> findByTenantIdAndStatus(UUID tenantId, SupplierStatus status, Pageable pageable);

    List<Supplier> findByTenantIdAndStatus(UUID tenantId, SupplierStatus status);

    boolean existsByTenantIdAndNameIgnoreCase(UUID tenantId, String name);

    boolean existsByTenantIdAndNameIgnoreCaseAndIdNot(UUID tenantId, String name, UUID id);

    boolean existsByTenantIdAndTaxIdIgnoreCase(UUID tenantId, String taxId);

    boolean existsByTenantIdAndTaxIdIgnoreCaseAndIdNot(UUID tenantId, String taxId, UUID id);
}

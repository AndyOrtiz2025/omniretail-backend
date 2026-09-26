package com.omniretail.backend.administration.repository;

import com.omniretail.backend.administration.entity.Supplier;
import com.omniretail.backend.administration.entity.SupplierStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierRepository extends JpaRepository<Supplier, UUID> {

    Optional<Supplier> findByTenantIdAndId(UUID tenantId, UUID id);

    Page<Supplier> findByTenantId(UUID tenantId, Pageable pageable);

    Page<Supplier> findByTenantIdAndStatus(UUID tenantId, SupplierStatus status, Pageable pageable);

    List<Supplier> findByTenantIdAndStatus(UUID tenantId, SupplierStatus status);

    Optional<Supplier> findByTenantIdAndNameIgnoreCase(UUID tenantId, String name);

    boolean existsByTenantIdAndNameIgnoreCase(UUID tenantId, String name);

    boolean existsByTenantIdAndNameIgnoreCaseAndIdNot(UUID tenantId, String name, UUID id);

    @Query(
            value = "SELECT * FROM suppliers WHERE tenant_id = :tenantId "
                    + "AND upper(regexp_replace(tax_id, '[\\s/-]', '', 'g')) = :normalizedTaxId",
            nativeQuery = true)
    Optional<Supplier> findByTenantIdAndNormalizedTaxId(
            @Param("tenantId") UUID tenantId, @Param("normalizedTaxId") String normalizedTaxId);

    @Query(
            value = "SELECT * FROM suppliers WHERE tenant_id = :tenantId "
                    + "AND upper(regexp_replace(tax_id, '[\\s/-]', '', 'g')) = :normalizedTaxId AND id <> :id",
            nativeQuery = true)
    Optional<Supplier> findByTenantIdAndNormalizedTaxIdAndIdNot(
            @Param("tenantId") UUID tenantId, @Param("normalizedTaxId") String normalizedTaxId, @Param("id") UUID id);
}

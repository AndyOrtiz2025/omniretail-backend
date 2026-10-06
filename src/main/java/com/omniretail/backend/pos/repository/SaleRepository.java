package com.omniretail.backend.pos.repository;

import com.omniretail.backend.pos.entity.Sale;
import com.omniretail.backend.pos.entity.SaleStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface SaleRepository extends JpaRepository<Sale, UUID>, JpaSpecificationExecutor<Sale> {

    Optional<Sale> findByTenantIdAndId(UUID tenantId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select sale from Sale sale where sale.tenantId = :tenantId and sale.id = :id")
    Optional<Sale> findByTenantIdAndIdForUpdate(UUID tenantId, UUID id);

    Optional<Sale> findByTenantIdAndNumber(UUID tenantId, String number);

    Optional<Sale> findByTenantIdAndConfirmationId(UUID tenantId, UUID confirmationId);

    List<Sale> findByTenantIdAndBranchIdOrderByCreatedAtDescIdDesc(UUID tenantId, UUID branchId);

    List<Sale> findByTenantIdAndStatusNot(UUID tenantId, SaleStatus status);

    List<Sale> findByTenantIdAndStatusNotAndCreatedAtGreaterThanEqual(
            UUID tenantId, SaleStatus status, Instant createdFrom);

    List<Sale> findByTenantIdAndCustomerIdAndStatusNot(UUID tenantId, UUID customerId, SaleStatus status);

    List<Sale> findByTenantId(UUID tenantId);

    List<Sale> findByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);

    Page<Sale> findByTenantIdAndBranchId(UUID tenantId, UUID branchId, Pageable pageable);

    Page<Sale> findByTenantIdAndBranchIdAndStatus(UUID tenantId, UUID branchId, SaleStatus status, Pageable pageable);

    Page<Sale> findByTenantIdAndBranchIdAndCreatedAtBetween(UUID tenantId, UUID branchId, Instant from, Instant to, Pageable pageable);

    Page<Sale> findByTenantIdAndBranchIdAndStatusAndCreatedAtBetween(UUID tenantId, UUID branchId, SaleStatus status, Instant from, Instant to, Pageable pageable);
}

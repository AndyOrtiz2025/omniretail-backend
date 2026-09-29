package com.omniretail.backend.pos.repository;

import com.omniretail.backend.pos.entity.CashShift;
import com.omniretail.backend.pos.entity.CashShiftStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface CashShiftRepository extends JpaRepository<CashShift, UUID> {

    Optional<CashShift> findByTenantIdAndId(UUID tenantId, UUID id);

    // Serializa cierres del mismo turno y limita la consulta al tenant y cajero autenticados.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select shift from CashShift shift where shift.tenantId = :tenantId and shift.userId = :userId and shift.id = :id")
    Optional<CashShift> findOwnedByIdForUpdate(UUID tenantId, UUID userId, UUID id);

    Optional<CashShift> findByTenantIdAndBranchIdAndUserIdAndStatus(
            UUID tenantId, UUID branchId, UUID userId, CashShiftStatus status);

    List<CashShift> findByTenantIdOrderByOpenedAtDesc(UUID tenantId);

    List<CashShift> findByTenantIdAndStatusOrderByOpenedAtDesc(UUID tenantId, CashShiftStatus status);

    List<CashShift> findByTenantIdAndBranchIdOrderByOpenedAtDesc(UUID tenantId, UUID branchId);

    List<CashShift> findByTenantIdAndBranchIdAndStatusOrderByOpenedAtDesc(
            UUID tenantId, UUID branchId, CashShiftStatus status);
}

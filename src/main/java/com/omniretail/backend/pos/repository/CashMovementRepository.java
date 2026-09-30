package com.omniretail.backend.pos.repository;

import com.omniretail.backend.pos.dto.CashMovementTotals;
import com.omniretail.backend.pos.entity.CashMovement;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CashMovementRepository extends JpaRepository<CashMovement, UUID> {

    List<CashMovement> findByTenantIdAndCashShiftIdOrderByCreatedAtAscIdAsc(
            UUID tenantId, UUID cashShiftId);

    @Query("""
            select new com.omniretail.backend.pos.dto.CashMovementTotals(
                coalesce(sum(case when movement.type = com.omniretail.backend.pos.entity.CashMovementType.in
                    then movement.amount else 0 end), 0),
                coalesce(sum(case when movement.type = com.omniretail.backend.pos.entity.CashMovementType.out
                    then movement.amount else 0 end), 0),
                coalesce(sum(case when movement.type = com.omniretail.backend.pos.entity.CashMovementType.in
                    and movement.referenceType is null and movement.referenceId is null
                    then movement.amount else 0 end), 0),
                coalesce(sum(case when movement.type = com.omniretail.backend.pos.entity.CashMovementType.out
                    and movement.referenceType is null and movement.referenceId is null
                    then movement.amount else 0 end), 0),
                coalesce(sum(case when movement.type = com.omniretail.backend.pos.entity.CashMovementType.in
                    and movement.referenceType = 'sale' then movement.amount else 0 end), 0),
                coalesce(sum(case when movement.type = com.omniretail.backend.pos.entity.CashMovementType.out
                    and movement.referenceType = 'sale_void' then movement.amount else 0 end), 0),
                coalesce(sum(case when movement.type = com.omniretail.backend.pos.entity.CashMovementType.out
                    and movement.referenceType = 'sale_return' then movement.amount else 0 end), 0))
            from CashMovement movement
            where movement.tenantId = :tenantId and movement.cashShiftId = :cashShiftId
            """)
    CashMovementTotals summarizeByTenantIdAndCashShiftId(UUID tenantId, UUID cashShiftId);

}

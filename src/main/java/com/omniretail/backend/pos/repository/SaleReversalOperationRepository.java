package com.omniretail.backend.pos.repository;

import com.omniretail.backend.pos.entity.SaleReversalOperation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SaleReversalOperationRepository
        extends JpaRepository<SaleReversalOperation, UUID> {

    Optional<SaleReversalOperation> findByTenantIdAndIdempotencyKey(
            UUID tenantId, UUID idempotencyKey);

    /** Serializa incluso claves nuevas, antes de que exista una fila protegible con FOR UPDATE. */
    @Query(
            value = "SELECT pg_advisory_xact_lock(hashtextextended(CAST(:lockKey AS text), 0))",
            nativeQuery = true)
    Object acquireIdempotencyLock(@Param("lockKey") String lockKey);
}

package com.omniretail.backend.pos.repository;

import com.omniretail.backend.pos.entity.DocumentCounter;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface DocumentCounterRepository extends JpaRepository<DocumentCounter, UUID> {

    @Modifying
    @Query(
            value = """
                    INSERT INTO document_counters (tenant_id, counter_key)
                    VALUES (:tenantId, :counterKey)
                    ON CONFLICT (tenant_id, counter_key) DO NOTHING
                    """,
            nativeQuery = true)
    void ensureExists(UUID tenantId, String counterKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<DocumentCounter> findByTenantIdAndCounterKey(UUID tenantId, String counterKey);
}

package com.omniretail.backend.pos.repository;

import com.omniretail.backend.pos.entity.DocumentCounter;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface DocumentCounterRepository extends JpaRepository<DocumentCounter, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<DocumentCounter> findByTenantIdAndCounterKey(UUID tenantId, String counterKey);
}

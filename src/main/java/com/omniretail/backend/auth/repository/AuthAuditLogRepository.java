package com.omniretail.backend.auth.repository;

import com.omniretail.backend.auth.entity.AuthAuditLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthAuditLogRepository extends JpaRepository<AuthAuditLog, UUID> {

    List<AuthAuditLog> findByAuthAccountIdOrderByCreatedAtAsc(UUID authAccountId);
}

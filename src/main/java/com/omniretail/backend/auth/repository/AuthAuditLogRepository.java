package com.omniretail.backend.auth.repository;

import com.omniretail.backend.auth.entity.AuthAuditLog;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AuthAuditLogRepository extends JpaRepository<AuthAuditLog, UUID> {

    List<AuthAuditLog> findByAuthAccountIdOrderByCreatedAtAsc(UUID authAccountId);

    /**
     * Eventos de la cuenta posteriores a {@code since}, del mas antiguo al mas reciente. Usa el indice
     * {@code idx_auth_audit_logs_account_created (auth_account_id, created_at)}.
     */
    @Query("""
            select l from AuthAuditLog l
            where l.authAccountId = :accountId and l.createdAt > :since and l.action in :actions
            order by l.createdAt asc, l.id asc""")
    List<AuthAuditLog> findRecentEvents(UUID accountId, Instant since, Collection<String> actions);
}

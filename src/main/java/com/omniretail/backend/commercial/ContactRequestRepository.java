package com.omniretail.backend.commercial;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContactRequestRepository extends JpaRepository<ContactRequest, UUID> {

    Optional<ContactRequest> findByIdempotencyKey(UUID idempotencyKey);

    Optional<ContactRequest> findByDedupeKeyAndDedupeDay(String dedupeKey, LocalDate dedupeDay);

    long countByIpHashAndCreatedAtAfter(String ipHash, Instant since);

    long countByEmailAndCreatedAtAfter(String email, Instant since);

    long countByStatusAndCreatedAtAfter(ContactRequestStatus status, Instant since);

    @Query(value = "SELECT * FROM contact_requests WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<ContactRequest> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Solicitudes con algun correo pendiente o fallido ya vencido, con SKIP LOCKED: varias instancias toman filas
     * distintas y un mismo correo no se envia dos veces a la vez.
     */
    @Query(value = """
            SELECT * FROM contact_requests
            WHERE status = 'RECEIVED'
              AND anonymized_at IS NULL
              AND (
                    (team_mail_status IN ('PENDING', 'FAILED')
                       AND team_mail_attempts < :maxAttempts
                       AND team_mail_next_attempt_at <= :now)
                 OR (confirmation_mail_status IN ('PENDING', 'FAILED')
                       AND confirmation_mail_attempts < :maxAttempts
                       AND confirmation_mail_next_attempt_at <= :now))
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ContactRequest> claimDue(
            @Param("now") Instant now, @Param("maxAttempts") int maxAttempts, @Param("batchSize") int batchSize);

    /**
     * Anonimiza los datos personales vencidos. Conserva id, estado, fechas e idempotency_key (para que un
     * reintento tardio siga devolviendo el mismo requestId sin datos). Lo pendiente de enviar ya no puede
     * enviarse: se descarta.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE contact_requests
            SET full_name = '[anonimizado]',
                business_name = '[anonimizado]',
                email = '[anonimizado]',
                phone = NULL,
                message = NULL,
                dedupe_key = NULL,
                ip_hash = NULL,
                team_mail_status = CASE WHEN team_mail_status IN ('PENDING', 'FAILED')
                                        THEN 'NOT_REQUIRED' ELSE team_mail_status END,
                team_mail_next_attempt_at = NULL,
                confirmation_mail_status = CASE WHEN confirmation_mail_status IN ('PENDING', 'FAILED')
                                                THEN 'NOT_REQUIRED' ELSE confirmation_mail_status END,
                confirmation_mail_next_attempt_at = NULL,
                anonymized_at = :now
            WHERE retention_until < :now
              AND anonymized_at IS NULL
            """, nativeQuery = true)
    int anonymizeExpired(@Param("now") Instant now);
}

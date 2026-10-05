package com.omniretail.backend.shared.notification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailDeliveryRepository extends JpaRepository<EmailDelivery, UUID> {

    @Query(value = "SELECT * FROM email_delivery WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<EmailDelivery> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Reclama filas vencidas con SKIP LOCKED: si hay varias instancias, cada una toma filas distintas y nunca
     * se envia dos veces el mismo correo. Solo filas con cuerpo cifrado (las de plataforma no se reintentan).
     */
    @Query(value = """
            SELECT * FROM email_delivery
            WHERE status IN ('PENDING', 'FAILED')
              AND next_attempt_at IS NOT NULL
              AND next_attempt_at <= :now
              AND attempts < :maxAttempts
              AND encrypted_payload IS NOT NULL
            ORDER BY next_attempt_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<EmailDelivery> claimDue(
            @Param("now") Instant now, @Param("maxAttempts") int maxAttempts, @Param("batchSize") int batchSize);
}

package com.omniretail.backend.auth.repository;

import com.omniretail.backend.auth.entity.Session;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SessionRepository extends JpaRepository<Session, UUID> {

    List<Session> findByUserIdAndRevokedAtIsNull(UUID userId);

    @Query("""
            select new com.omniretail.backend.auth.repository.SessionWithOwner(s, u.type, u.tenantId)
            from Session s join User u on u.id = s.userId
            where s.id = :sessionId""")
    Optional<SessionWithOwner> findWithOwner(UUID sessionId);

    /**
     * Renueva la ultima actividad solo si la guardada es anterior a {@code notAfter}: como mucho una escritura
     * por intervalo, y atomica (dos requests simultaneos no escriben dos veces).
     */
    @Modifying
    @Query("""
            update Session s set s.lastActivityAt = :now
            where s.id = :sessionId and s.revokedAt is null and s.lastActivityAt < :notAfter""")
    int touchActivity(UUID sessionId, Instant now, Instant notAfter);

    /** Revoca la sesion si sigue activa; devuelve 1 solo al request que la revoco. */
    @Modifying
    @Query("update Session s set s.revokedAt = :now where s.id = :sessionId and s.revokedAt is null")
    int revokeIfActive(UUID sessionId, Instant now);
}

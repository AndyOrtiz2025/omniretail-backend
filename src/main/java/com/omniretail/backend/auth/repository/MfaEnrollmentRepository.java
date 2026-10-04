package com.omniretail.backend.auth.repository;

import com.omniretail.backend.auth.entity.MfaEnrollment;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface MfaEnrollmentRepository extends JpaRepository<MfaEnrollment, UUID> {

    Optional<MfaEnrollment> findByUserId(UUID userId);

    /** Serializa la activacion y la verificacion de codigos del mismo usuario (evita aceptar dos veces un codigo). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from MfaEnrollment e where e.userId = :userId")
    Optional<MfaEnrollment> findByUserIdForUpdate(UUID userId);

    boolean existsByUserIdAndEnabledTrue(UUID userId);

    @Query("select e.userId from MfaEnrollment e where e.enabled = true and e.userId in :userIds")
    List<UUID> findEnabledUserIds(Collection<UUID> userIds);
}

package com.omniretail.backend.auth.repository;

import com.omniretail.backend.auth.entity.EmployeeInvitation;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface EmployeeInvitationRepository extends JpaRepository<EmployeeInvitation, UUID> {

    /** SELECT ... FOR UPDATE: serializa la aceptacion de un mismo token. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from EmployeeInvitation i where i.tokenHash = :tokenHash")
    Optional<EmployeeInvitation> findByTokenHashForUpdate(String tokenHash);

    /** Bloquea las invitaciones pendientes antes de reemplazarlas por una nueva. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<EmployeeInvitation> findByUserIdAndAcceptedAtIsNullAndSupersededAtIsNull(UUID userId);

    Optional<EmployeeInvitation> findTopByUserIdOrderByCreatedAtDesc(UUID userId);
}

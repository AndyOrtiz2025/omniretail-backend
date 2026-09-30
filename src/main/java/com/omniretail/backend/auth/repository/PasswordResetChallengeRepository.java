package com.omniretail.backend.auth.repository;

import com.omniretail.backend.auth.entity.PasswordResetChallenge;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PasswordResetChallengeRepository extends JpaRepository<PasswordResetChallenge, UUID> {

    /** SELECT ... FOR UPDATE: dos resets simultaneos con el mismo token no pueden usarlo ambos. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PasswordResetChallenge c where c.tokenHash = :tokenHash")
    Optional<PasswordResetChallenge> findByTokenHashForUpdate(String tokenHash);

    long countByUserIdAndCreatedAtAfter(UUID userId, Instant createdAfter);

    List<PasswordResetChallenge> findByUserIdAndUsedAtIsNullAndSupersededAtIsNull(UUID userId);

    List<PasswordResetChallenge> findByUserId(UUID userId);
}

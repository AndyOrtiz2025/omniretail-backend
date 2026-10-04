package com.omniretail.backend.auth.repository;

import com.omniretail.backend.auth.entity.MfaRecoveryCode;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from MfaRecoveryCode r where r.userId = :userId and r.codeHash = :codeHash and r.usedAt is null")
    Optional<MfaRecoveryCode> findUnusedForUpdate(UUID userId, String codeHash);

    List<MfaRecoveryCode> findByUserId(UUID userId);

    @Modifying(flushAutomatically = true)
    @Query("delete from MfaRecoveryCode r where r.userId = :userId")
    void deleteAllByUserId(UUID userId);
}

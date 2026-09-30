package com.omniretail.backend.auth.repository;

import com.omniretail.backend.auth.entity.EmailVerification;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, UUID> {

    /** SELECT ... FOR UPDATE: dos verificaciones simultaneas del mismo token no pueden usarlo ambas. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from EmailVerification v where v.tokenHash = :tokenHash")
    Optional<EmailVerification> findByTokenHashForUpdate(String tokenHash);

    List<EmailVerification> findByUserId(UUID userId);
}

package com.omniretail.backend.auth.repository;

import com.omniretail.backend.auth.entity.MfaChallenge;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface MfaChallengeRepository extends JpaRepository<MfaChallenge, UUID> {

    /** Dos verificaciones simultaneas del mismo desafio se ejecutan de a una: solo una puede consumirlo. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from MfaChallenge c where c.tokenHash = :tokenHash")
    Optional<MfaChallenge> findByTokenHashForUpdate(String tokenHash);

    List<MfaChallenge> findByUserIdAndConsumedAtIsNullAndInvalidatedAtIsNull(UUID userId);
}

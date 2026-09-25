package com.omniretail.backend.auth.repository;

import com.omniretail.backend.auth.entity.AuthAccount;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AuthAccountRepository extends JpaRepository<AuthAccount, UUID> {

    List<AuthAccount> findByEmail(String email);

    Optional<AuthAccount> findByUserId(UUID userId);

    /** SELECT ... FOR UPDATE: evita perder incrementos de intentos fallidos con logins concurrentes. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AuthAccount a where a.id = :id")
    Optional<AuthAccount> findForUpdate(UUID id);
}

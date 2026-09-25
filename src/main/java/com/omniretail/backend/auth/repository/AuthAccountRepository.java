package com.omniretail.backend.auth.repository;

import com.omniretail.backend.auth.entity.AuthAccount;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthAccountRepository extends JpaRepository<AuthAccount, UUID> {

    List<AuthAccount> findByEmail(String email);

    Optional<AuthAccount> findByUserId(UUID userId);
}

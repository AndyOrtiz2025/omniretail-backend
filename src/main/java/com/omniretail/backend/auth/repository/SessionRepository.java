package com.omniretail.backend.auth.repository;

import com.omniretail.backend.auth.entity.Session;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionRepository extends JpaRepository<Session, UUID> {

    List<Session> findByUserIdAndRevokedAtIsNull(UUID userId);
}

package com.omniretail.backend.administration.repository;

import com.omniretail.backend.administration.entity.Tenant;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    Optional<Tenant> findBySlug(String slug);

    boolean existsBySlug(String slug);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select tenant from Tenant tenant where tenant.id = :id")
    Optional<Tenant> findByIdForUpdate(UUID id);
}

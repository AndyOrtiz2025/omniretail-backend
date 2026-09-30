package com.omniretail.backend.administration.repository;

import com.omniretail.backend.administration.entity.SaasPlan;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface SaasPlanRepository extends JpaRepository<SaasPlan, UUID> {

    Optional<SaasPlan> findByCode(String code);

    Optional<SaasPlan> findByCodeIgnoreCase(String code);

    Optional<SaasPlan> findByIdAndActiveTrue(UUID id);

    List<SaasPlan> findByActiveTrueOrderByNameAsc();

    List<SaasPlan> findAllByOrderByNameAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select plan from SaasPlan plan where plan.id = :id")
    Optional<SaasPlan> findByIdForUpdate(UUID id);
}

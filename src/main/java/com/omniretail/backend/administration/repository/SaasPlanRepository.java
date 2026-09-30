package com.omniretail.backend.administration.repository;

import com.omniretail.backend.administration.entity.SaasPlan;
import com.omniretail.backend.administration.entity.PlanStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaasPlanRepository extends JpaRepository<SaasPlan, UUID> {
    Optional<SaasPlan> findByCode(String code);
    List<SaasPlan> findByStatusOrderByNameAsc(PlanStatus status);
    List<SaasPlan> findAllByOrderByNameAsc();
}

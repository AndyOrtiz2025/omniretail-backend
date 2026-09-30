package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.dto.SaasPlanResponse;
import com.omniretail.backend.administration.entity.PlanStatus;
import com.omniretail.backend.administration.repository.SaasPlanRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SaasPlanService {
    private final SaasPlanRepository planRepository;

    public List<SaasPlanResponse> list(boolean activeOnly) {
        return (activeOnly ? planRepository.findByStatusOrderByNameAsc(PlanStatus.active)
                : planRepository.findAllByOrderByNameAsc()).stream().map(SaasPlanResponse::from).toList();
    }

    public SaasPlanResponse get(UUID id) {
        return SaasPlanResponse.from(planRepository.findById(id).orElseThrow(() ->
                new BusinessException(HttpStatus.NOT_FOUND, "SAAS_PLAN_NOT_FOUND", "Plan SaaS no encontrado.")));
    }
}

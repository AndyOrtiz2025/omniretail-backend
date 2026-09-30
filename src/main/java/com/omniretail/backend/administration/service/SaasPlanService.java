package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.dto.CreateSaasPlanRequest;
import com.omniretail.backend.administration.dto.SaasPlanResponse;
import com.omniretail.backend.administration.dto.UpdateSaasPlanRequest;
import com.omniretail.backend.administration.entity.SaasPlan;
import com.omniretail.backend.administration.repository.SaasPlanRepository;
import com.omniretail.backend.administration.repository.TenantSubscriptionRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.SaasCapability;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class SaasPlanService {

    private static final String CODE_CONSTRAINT = "uk_saas_plans_code";
    private static final Set<String> SUPPORTED_CAPABILITIES = Arrays.stream(SaasCapability.values())
            .map(SaasCapability::getKey)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final SaasPlanRepository planRepository;
    private final TenantSubscriptionRepository subscriptionRepository;
    private final PlatformTenantGuard platformTenantGuard;

    @Transactional(readOnly = true)
    public List<SaasPlanResponse> list(boolean activeOnly) {
        List<SaasPlan> plans = activeOnly
                ? planRepository.findByActiveTrueOrderByNameAsc()
                : planRepository.findAllByOrderByNameAsc();
        return plans.stream().map(SaasPlanResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public SaasPlanResponse get(UUID id) {
        return SaasPlanResponse.from(requirePlan(id));
    }

    public SaasPlanResponse create(CreateSaasPlanRequest request) {
        platformTenantGuard.requirePlatformTenant();
        String code = normalizeCode(request.code());
        ensureCodeAvailable(code, null);
        SaasPlan plan = SaasPlan.builder()
                .code(code)
                .name(request.name().trim())
                .description(normalize(request.description()))
                .maxBranches(request.maxBranches())
                .maxUsers(request.maxUsers())
                .maxProducts(request.maxProducts())
                .priceMonthly(request.priceMonthly())
                .currency(request.currency())
                .capabilities(normalizeCapabilities(request.capabilities()))
                .active(true)
                .build();
        return SaasPlanResponse.from(saveWithCodeConflictTranslation(plan));
    }

    public SaasPlanResponse update(UUID id, UpdateSaasPlanRequest request) {
        platformTenantGuard.requirePlatformTenant();
        SaasPlan plan = requirePlanForUpdate(id);
        String code = normalizeCode(request.code());
        ensureCodeAvailable(code, id);
        plan.setCode(code);
        plan.setName(request.name().trim());
        plan.setDescription(normalize(request.description()));
        plan.setMaxBranches(request.maxBranches());
        plan.setMaxUsers(request.maxUsers());
        plan.setMaxProducts(request.maxProducts());
        plan.setPriceMonthly(request.priceMonthly());
        plan.setCurrency(request.currency());
        plan.setCapabilities(normalizeCapabilities(request.capabilities()));
        return SaasPlanResponse.from(saveWithCodeConflictTranslation(plan));
    }

    public SaasPlanResponse activate(UUID id) {
        return setActive(id, true);
    }

    public SaasPlanResponse deactivate(UUID id) {
        return setActive(id, false);
    }

    public void delete(UUID id) {
        platformTenantGuard.requirePlatformTenant();
        SaasPlan plan = requirePlanForUpdate(id);
        if (subscriptionRepository.existsByPlanId(id)) {
            throw BusinessException.conflict(
                    "SAAS_PLAN_IN_USE", "El plan no puede eliminarse porque tiene suscripciones asociadas.");
        }
        planRepository.delete(plan);
    }

    private SaasPlanResponse setActive(UUID id, boolean active) {
        platformTenantGuard.requirePlatformTenant();
        SaasPlan plan = requirePlanForUpdate(id);
        plan.setActive(active);
        return SaasPlanResponse.from(planRepository.save(plan));
    }

    private SaasPlan requirePlan(UUID id) {
        return planRepository.findById(id)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "SAAS_PLAN_NOT_FOUND", "Plan SaaS no encontrado."));
    }

    private SaasPlan requirePlanForUpdate(UUID id) {
        return planRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "SAAS_PLAN_NOT_FOUND", "Plan SaaS no encontrado."));
    }

    private void ensureCodeAvailable(String code, UUID currentId) {
        planRepository.findByCodeIgnoreCase(code).ifPresent(existing -> {
            if (currentId == null || !existing.getId().equals(currentId)) {
                throw BusinessException.conflict("SAAS_PLAN_CODE_EXISTS", "Ya existe un plan con el codigo " + code + ".");
            }
        });
    }

    private SaasPlan saveWithCodeConflictTranslation(SaasPlan plan) {
        try {
            SaasPlan saved = planRepository.save(plan);
            planRepository.flush();
            return saved;
        } catch (DataIntegrityViolationException exception) {
            if (exceptionDetail(exception).contains(CODE_CONSTRAINT)) {
                throw BusinessException.conflict(
                        "SAAS_PLAN_CODE_EXISTS", "Ya existe un plan con el codigo " + plan.getCode() + ".");
            }
            throw exception;
        }
    }

    private static String exceptionDetail(Throwable exception) {
        StringBuilder detail = new StringBuilder();
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                detail.append(' ').append(current.getMessage().toLowerCase(Locale.ROOT));
            }
        }
        return detail.toString();
    }

    private static List<String> normalizeCapabilities(List<String> capabilities) {
        Set<String> normalized = new HashSet<>();
        for (String capability : capabilities) {
            String key = capability.trim();
            if (!SUPPORTED_CAPABILITIES.contains(key)) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "SAAS_CAPABILITY_INVALID",
                        "La capacidad " + key + " no esta soportada.");
            }
            normalized.add(key);
        }
        return normalized.stream().sorted().toList();
    }

    private static String normalizeCode(String code) {
        return code.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }
}

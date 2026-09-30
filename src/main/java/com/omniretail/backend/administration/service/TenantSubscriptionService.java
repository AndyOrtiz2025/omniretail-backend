package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.dto.TenantSubscriptionDetailsResponse;
import com.omniretail.backend.administration.dto.TenantSubscriptionDetailsResponse.AddonLine;
import com.omniretail.backend.administration.dto.TenantSubscriptionDetailsResponse.Capability;
import com.omniretail.backend.administration.dto.TenantSubscriptionDetailsResponse.Invoice;
import com.omniretail.backend.administration.dto.TenantSubscriptionDetailsResponse.Plan;
import com.omniretail.backend.administration.dto.TenantSubscriptionDetailsResponse.SelectablePlan;
import com.omniretail.backend.administration.dto.TenantSubscriptionDetailsResponse.Subscription;
import com.omniretail.backend.administration.dto.TenantSubscriptionDetailsResponse.Usage;
import com.omniretail.backend.administration.dto.UpdateSubscriptionAddonsRequest;
import com.omniretail.backend.administration.entity.PlanStatus;
import com.omniretail.backend.administration.entity.SaasPlan;
import com.omniretail.backend.administration.entity.SubscriptionInvoice;
import com.omniretail.backend.administration.entity.TenantSubscription;
import com.omniretail.backend.administration.entity.TenantSubscriptionStatus;
import com.omniretail.backend.administration.repository.EcommerceConfigRepository;
import com.omniretail.backend.administration.repository.SaasPlanRepository;
import com.omniretail.backend.administration.repository.SubscriptionInvoiceRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.TenantSubscriptionRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional
@RequiredArgsConstructor
public class TenantSubscriptionService {
    private static final Set<TenantSubscriptionStatus> CURRENT_STATUSES =
            EnumSet.of(TenantSubscriptionStatus.active, TenantSubscriptionStatus.suspended);
    private static final TypeReference<List<AddonLine>> ADDON_LINES = new TypeReference<>() {};
    private final TenantSubscriptionRepository subscriptionRepository;
    private final SaasPlanRepository planRepository;
    private final TenantRepository tenantRepository;
    private final SubscriptionInvoiceRepository invoiceRepository;
    private final EcommerceConfigRepository ecommerceConfigRepository;
    private final PlanLimitGuard planLimitGuard;
    private final CurrentUser currentUser;
    private final JsonMapper jsonMapper;
    private final Clock subscriptionClock;

    public TenantSubscriptionDetailsResponse getCurrent() {
        UUID tenantId = currentUser.require().tenantId();
        TenantSubscription subscription = lockSubscription(tenantId);
        SaasPlan plan = requirePlan(subscription.getPlanId());
        var cycle = ensureCurrentInvoice(subscription, plan);
        return response(subscription, plan, cycle);
    }

    public TenantSubscriptionDetailsResponse updateAddons(UpdateSubscriptionAddonsRequest request) {
        List<String> next = SubscriptionAddonCatalog.normalize(request.addonCodes());
        UUID tenantId = currentUser.require().tenantId();
        TenantSubscription subscription = lockSubscription(tenantId);
        SaasPlan plan = requirePlan(subscription.getPlanId());
        if (plan.getStatus() != PlanStatus.active) {
            throw BusinessException.conflict("SAAS_PLAN_ARCHIVED", "El plan base no está disponible.");
        }
        // Keep the current cycle snapshot unchanged; commercial access changes immediately.
        var cycle = ensureCurrentInvoice(subscription, plan);
        if (!next.equals(subscription.getAddonCodes())) {
            subscription.setAddonCodes(next);
            subscriptionRepository.save(subscription);
        }
        return response(subscription, plan, cycle);
    }

    private TenantSubscription lockSubscription(UUID tenantId) {
        tenantRepository.findByIdForUpdate(tenantId).orElseThrow(() ->
                new BusinessException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "Tenant no encontrado."));
        return subscriptionRepository.findCurrentByTenantIdForUpdate(tenantId, CURRENT_STATUSES)
                .or(() -> subscriptionRepository.findFirstByTenantIdOrderByStartedAtDescCreatedAtDesc(tenantId))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "TENANT_SUBSCRIPTION_NOT_FOUND", "El negocio activo no tiene una suscripción configurada."));
    }

    private SaasPlan requirePlan(UUID planId) {
        return planRepository.findById(planId).orElseThrow(() ->
                new BusinessException(HttpStatus.NOT_FOUND, "SAAS_PLAN_NOT_FOUND", "Plan SaaS no encontrado."));
    }

    private SubscriptionBillingCycle ensureCurrentInvoice(TenantSubscription subscription, SaasPlan plan) {
        var cycle = SubscriptionBillingCycle.at(subscription.getStartedAt(), subscriptionClock.instant());
        if (subscription.getStatus() != TenantSubscriptionStatus.active) return cycle;
        // These are calendar metadata for simulated billing, never evidence of payment.
        subscription.setCurrentPeriodStart(cycle.start());
        subscription.setCurrentPeriodEnd(cycle.end());
        if (invoiceRepository.findByTenantIdAndSubscriptionIdAndCycleStart(
                        subscription.getTenantId(), subscription.getId(), cycle.start()).isEmpty()) {
            List<String> codes = List.copyOf(subscription.getAddonCodes());
            List<AddonLine> lines = SubscriptionAddonCatalog.ADDONS.stream()
                    .filter(addon -> codes.contains(addon.code()))
                    .map(addon -> new AddonLine(addon.code(), addon.name(), addon.monthlyQuetzales())).toList();
            BigDecimal total = lines.stream().map(AddonLine::amountQuetzales)
                    .reduce(plan.getMonthlyQuetzales(), BigDecimal::add);
            SubscriptionInvoice invoice = SubscriptionInvoice.builder()
                    .subscriptionId(subscription.getId()).cycleStart(cycle.start()).cycleEnd(cycle.end())
                    .addonCodes(codes).baseQuetzales(plan.getMonthlyQuetzales()).totalQuetzales(total)
                    .addonLinesJson(jsonMapper.writeValueAsString(lines)).status("simulated").build();
            invoice.setTenantId(subscription.getTenantId());
            invoiceRepository.save(invoice);
        }
        return cycle;
    }

    private TenantSubscriptionDetailsResponse response(TenantSubscription subscription, SaasPlan plan, SubscriptionBillingCycle cycle) {
        UUID tenantId = subscription.getTenantId();
        Set<String> included = new HashSet<>();
        // Presentation shows commercial inclusions; authorization uses the entitlement resolver.
        included.addAll(plan.getCapabilities());
        included.addAll(SubscriptionAddonCatalog.capabilities(subscription.getAddonCodes()));
        String ecommerceStatus = included.contains("ecommerce")
                ? ecommerceConfigRepository.findByTenantId(tenantId)
                        .map(config -> config.isEnabled() ? "Tienda activada" : "Tienda desactivada").orElse("Tienda desactivada")
                : null;
        List<Capability> capabilities = Arrays.stream(SaasCapability.values())
                .map(capability -> new Capability(capability.getKey(), included.contains(capability.getKey()),
                        capability == SaasCapability.ecommerce ? ecommerceStatus : null)).toList();
        var usage = planLimitGuard.evaluateUsage(tenantId, plan);
        List<Invoice> invoices = invoiceRepository.findByTenantIdAndSubscriptionIdOrderByCycleStartDesc(
                tenantId, subscription.getId()).stream().map(invoice ->
                        new Invoice(invoice.getId(), invoice.getTenantId(), invoice.getCycleStart(), invoice.getCycleEnd(),
                                invoice.getCreatedAt(), invoice.getAddonCodes(), invoice.getBaseQuetzales(),
                                jsonMapper.readValue(invoice.getAddonLinesJson(), ADDON_LINES), invoice.getTotalQuetzales(), invoice.getStatus())).toList();
        List<SelectablePlan> availablePlans = planRepository.findByStatusOrderByNameAsc(PlanStatus.active).stream()
                .map(active -> new SelectablePlan(active.getId(), active.getCode(), active.getName(),
                        active.getDescription(), active.getCapabilities())).toList();
        return new TenantSubscriptionDetailsResponse(tenantId, List.copyOf(subscription.getAddonCodes()), cycle.end(), invoices,
                new Subscription(subscription.getStatus(), subscription.getStartedAt()),
                new Plan(plan.getId(), plan.getCode(), plan.getName(), plan.getDescription()), capabilities,
                List.of(new Usage("maxEmployees", usage.maxEmployees().current(), usage.maxEmployees().limit()),
                        new Usage("maxBranches", usage.maxBranches().current(), usage.maxBranches().limit())), availablePlans);
    }
}

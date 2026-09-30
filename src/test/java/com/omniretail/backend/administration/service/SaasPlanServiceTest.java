package com.omniretail.backend.administration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.omniretail.backend.administration.dto.CreateSaasPlanRequest;
import com.omniretail.backend.administration.dto.UpdateSaasPlanRequest;
import com.omniretail.backend.administration.entity.SaasPlan;
import com.omniretail.backend.administration.entity.SaasPlanCurrency;
import com.omniretail.backend.administration.repository.SaasPlanRepository;
import com.omniretail.backend.administration.repository.TenantSubscriptionRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SaasPlanServiceTest {

    @Mock private SaasPlanRepository plans;
    @Mock private TenantSubscriptionRepository subscriptions;
    @Mock private PlatformTenantGuard platformTenantGuard;
    @InjectMocks private SaasPlanService service;

    @Test
    void listsActivePlansInRepositoryOrder() {
        SaasPlan starter = plan(UUID.randomUUID(), "starter", true);
        SaasPlan growth = plan(UUID.randomUUID(), "growth", true);
        when(plans.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(growth, starter));

        assertThat(service.list(true)).extracting(response -> response.code())
                .containsExactly("growth", "starter");
        verify(plans, never()).findAllByOrderByNameAsc();
    }

    @Test
    void createsNormalizedPlanAndDeduplicatesCapabilities() {
        when(plans.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create(new CreateSaasPlanRequest(
                "starter",
                "  Starter  ",
                "  Para tiendas pequenas  ",
                1,
                3,
                100,
                new BigDecimal("19.90"),
                SaasPlanCurrency.USD,
                List.of("pos", "inventory", "pos")));

        assertThat(response.name()).isEqualTo("Starter");
        assertThat(response.description()).isEqualTo("Para tiendas pequenas");
        assertThat(response.capabilities()).containsExactly("inventory", "pos");
        verify(platformTenantGuard).requirePlatformTenant();
        ArgumentCaptor<SaasPlan> captor = ArgumentCaptor.forClass(SaasPlan.class);
        verify(plans).save(captor.capture());
        assertThat(captor.getValue().isActive()).isTrue();
    }

    @Test
    void rejectsUnknownCapabilityWithoutPersisting() {
        CreateSaasPlanRequest request = new CreateSaasPlanRequest(
                "starter", "Starter", null, 1, 3, 100, BigDecimal.ZERO,
                SaasPlanCurrency.USD, List.of("inventory", "unknown.capability"));

        assertCode(() -> service.create(request), "SAAS_CAPABILITY_INVALID");
        verify(plans, never()).save(any());
    }

    @Test
    void translatesConcurrentDuplicateCodeDetectedOnFlush() {
        when(plans.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new DataIntegrityViolationException("uk_saas_plans_code")).when(plans).flush();
        CreateSaasPlanRequest request = new CreateSaasPlanRequest(
                "starter", "Starter", null, 1, 3, 100, BigDecimal.ZERO,
                SaasPlanCurrency.USD, List.of("pos"));

        assertCode(() -> service.create(request), "SAAS_PLAN_CODE_EXISTS");
    }

    @Test
    void updateUsesPessimisticLookupAndAllowsOwnCode() {
        UUID id = UUID.randomUUID();
        SaasPlan plan = plan(id, "starter", true);
        when(plans.findByIdForUpdate(id)).thenReturn(Optional.of(plan));
        when(plans.findByCodeIgnoreCase("starter")).thenReturn(Optional.of(plan));
        when(plans.save(plan)).thenReturn(plan);

        service.update(id, new UpdateSaasPlanRequest(
                "starter", "Starter Plus", " ", 2, 5, 200,
                new BigDecimal("29.90"), SaasPlanCurrency.USD, List.of("pos")));

        verify(plans).findByIdForUpdate(id);
        assertThat(plan.getName()).isEqualTo("Starter Plus");
        assertThat(plan.getDescription()).isNull();
        assertThat(plan.getCapabilities()).containsExactly("pos");
    }

    @Test
    void rejectsDuplicateCodeOwnedByAnotherPlan() {
        UUID id = UUID.randomUUID();
        SaasPlan current = plan(id, "starter", true);
        SaasPlan duplicate = plan(UUID.randomUUID(), "growth", true);
        when(plans.findByIdForUpdate(id)).thenReturn(Optional.of(current));
        when(plans.findByCodeIgnoreCase("growth")).thenReturn(Optional.of(duplicate));

        UpdateSaasPlanRequest request = new UpdateSaasPlanRequest(
                "growth", "Starter", null, 1, 3, 100, BigDecimal.ZERO,
                SaasPlanCurrency.USD, List.of("pos"));
        assertCode(() -> service.update(id, request), "SAAS_PLAN_CODE_EXISTS");
        verify(plans, never()).save(any());
    }

    @Test
    void deleteRejectsPlanUsedByAnyTenant() {
        UUID id = UUID.randomUUID();
        SaasPlan plan = plan(id, "starter", true);
        when(plans.findByIdForUpdate(id)).thenReturn(Optional.of(plan));
        when(subscriptions.existsByPlanId(id)).thenReturn(true);

        assertCode(() -> service.delete(id), "SAAS_PLAN_IN_USE");
        verify(plans, never()).delete(any());
    }

    @Test
    void activateAndDeactivateUseLockedLookup() {
        UUID id = UUID.randomUUID();
        SaasPlan plan = plan(id, "starter", true);
        when(plans.findByIdForUpdate(id)).thenReturn(Optional.of(plan));
        when(plans.save(plan)).thenReturn(plan);

        assertThat(service.deactivate(id).active()).isFalse();
        assertThat(service.activate(id).active()).isTrue();
        verify(plans, org.mockito.Mockito.times(2)).findByIdForUpdate(id);
    }

    private static SaasPlan plan(UUID id, String code, boolean active) {
        SaasPlan plan = SaasPlan.builder()
                .code(code)
                .name(code)
                .maxBranches(1)
                .maxUsers(3)
                .maxProducts(100)
                .priceMonthly(BigDecimal.TEN)
                .currency(SaasPlanCurrency.USD)
                .capabilities(List.of("pos"))
                .active(active)
                .build();
        ReflectionTestUtils.setField(plan, "id", id);
        return plan;
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(code));
    }
}

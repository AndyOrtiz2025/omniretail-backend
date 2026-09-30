package com.omniretail.backend.administration.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.omniretail.backend.administration.entity.PlanStatus;
import com.omniretail.backend.administration.entity.SaasPlan;
import com.omniretail.backend.administration.repository.SaasPlanRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SaasPlanServiceTest {
    @Mock private SaasPlanRepository plans;
    @InjectMocks private SaasPlanService service;

    @Test
    void listActiveCatalogPreservesUnlimitedLimitsAndQuetzales() {
        when(plans.findByStatusOrderByNameAsc(PlanStatus.active)).thenReturn(List.of(basic()));
        var result = service.list(true);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().monthlyQuetzales()).isEqualByComparingTo("199.00");
        assertThat(result.getFirst().limits()).isEmpty();
        verify(plans, never()).findAllByOrderByNameAsc();
    }

    @Test
    void listCanIncludeArchivedPlans() {
        when(plans.findAllByOrderByNameAsc()).thenReturn(List.of(basic()));
        assertThat(service.list(false)).hasSize(1);
        verify(plans, never()).findByStatusOrderByNameAsc(any());
    }

    @Test
    void missingPlanReturnsNotFound() {
        UUID id = UUID.randomUUID();
        when(plans.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(id)).isInstanceOf(BusinessException.class);
    }

    @Test
    void getReturnsSeedCatalogContract() {
        SaasPlan plan = basic();
        UUID id = UUID.randomUUID();
        plan.setId(id);
        when(plans.findById(id)).thenReturn(Optional.of(plan));
        assertThat(service.get(id).code()).isEqualTo("basic");
    }

    private SaasPlan basic() {
        return SaasPlan.builder().code("basic").name("Plan Básico")
                .monthlyQuetzales(new BigDecimal("199.00")).status(PlanStatus.active)
                .capabilities(List.of("inventory", "pos")).build();
    }
}

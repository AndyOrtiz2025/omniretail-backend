package com.omniretail.backend.administration.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.omniretail.backend.SubscriptionTestFixtures;
import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.BranchStatus;
import com.omniretail.backend.administration.entity.BranchType;
import com.omniretail.backend.administration.entity.PlanStatus;
import com.omniretail.backend.administration.entity.SaasPlan;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.repository.SaasPlanRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.TenantSubscriptionRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PlanLimitGuardConcurrencyTest {
    @Autowired private PlanLimitGuard guard;
    @Autowired private TenantRepository tenants;
    @Autowired private SaasPlanRepository plans;
    @Autowired private TenantSubscriptionRepository subscriptions;
    @Autowired private BranchRepository branches;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void twoSimultaneousBranchCreationsCannotConsumeTheLastSlotTwice() throws Exception {
        Tenant tenant = tenants.saveAndFlush(Tenant.builder().name("Concurrency limits")
                .slug("limits-" + UUID.randomUUID()).defaultCurrency("GTQ")
                .timezone("America/Guatemala").status(TenantStatus.active).build());
        SaasPlan plan = plans.saveAndFlush(SaasPlan.builder().code("limited-" + UUID.randomUUID())
                .name("One branch").monthlyQuetzales(new BigDecimal("199.00"))
                .status(PlanStatus.active).capabilities(List.of()).maxBranches(1).build());
        var subscription = SubscriptionTestFixtures.provisionBasic(subscriptions, plans, tenant.getId());
        subscription.setPlanId(plan.getId());
        subscriptions.saveAndFlush(subscription);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> createBranch(tenant.getId(), ready, start));
            var second = executor.submit(() -> createBranch(tenant.getId(), ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("created", "LIMIT_REACHED");
            assertThat(branches.countByTenantIdAndStatusNot(tenant.getId(), BranchStatus.archived)).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private String createBranch(UUID tenantId, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent creation did not start");
        }
        try {
            return new TransactionTemplate(transactionManager).execute(status -> {
                guard.ensureBranchCreationAllowed(tenantId);
                Branch branch = Branch.builder().code("B-" + UUID.randomUUID()).name("Concurrent")
                        .type(BranchType.store).status(BranchStatus.active).build();
                branch.setTenantId(tenantId);
                branches.saveAndFlush(branch);
                return "created";
            });
        } catch (BusinessException exception) {
            return exception.getCode();
        }
    }
}

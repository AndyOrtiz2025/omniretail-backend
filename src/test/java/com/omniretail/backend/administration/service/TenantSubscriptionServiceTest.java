package com.omniretail.backend.administration.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.omniretail.backend.administration.dto.UpdateSubscriptionAddonsRequest;
import com.omniretail.backend.administration.entity.*;
import com.omniretail.backend.administration.repository.*;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class TenantSubscriptionServiceTest {
 @Mock TenantSubscriptionRepository subscriptions;
 @Mock SaasPlanRepository plans;
 @Mock TenantRepository tenants;
 @Mock SubscriptionInvoiceRepository invoices;
 @Mock EcommerceConfigRepository ecommerce;
 @Mock PlanLimitGuard limits;
 @Mock CurrentUser currentUser;
 TenantSubscriptionService service;
 UUID tenantId = UUID.randomUUID();
 UUID planId = UUID.randomUUID();
 TenantSubscription subscription;
 SaasPlan plan;
 Clock clock = Clock.fixed(Instant.parse("2025-02-28T10:00:00Z"), ZoneOffset.UTC);

 @BeforeEach void setUp() {
  service = new TenantSubscriptionService(subscriptions, plans, tenants, invoices, ecommerce, limits, currentUser, JsonMapper.builder().build(), clock);
  lenient().when(currentUser.require()).thenReturn(new AuthenticatedUser(UUID.randomUUID(), tenantId, UserType.employee, UUID.randomUUID(), null, UUID.randomUUID()));
  subscription = TenantSubscription.builder().planId(planId).status(TenantSubscriptionStatus.active)
    .startedAt(Instant.parse("2025-01-31T00:00:00Z")).currentPeriodStart(Instant.parse("2025-01-31T00:00:00Z"))
    .currentPeriodEnd(Instant.parse("2025-02-28T00:00:00Z")).addonCodes(List.of()).build();
  subscription.setTenantId(tenantId);
  ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
  plan = SaasPlan.builder().code("basic").name("Básico").monthlyQuetzales(new BigDecimal("199.00"))
    .status(PlanStatus.active).capabilities(List.of("pos")).build();
  ReflectionTestUtils.setField(plan, "id", planId);
 }

 void stubSnapshot() {
  stubSnapshot(Optional.of(subscription));
 }

 void stubSnapshot(Optional<TenantSubscription> current) {
  when(tenants.findByIdForUpdate(tenantId)).thenReturn(Optional.of(Tenant.builder().build()));
  when(subscriptions.findCurrentByTenantIdForUpdate(eq(tenantId), anyCollection())).thenReturn(current);
  when(plans.findById(planId)).thenReturn(Optional.of(plan));
  when(limits.evaluateUsage(tenantId, plan)).thenReturn(new PlanLimitGuard.PlanUsage(
    new PlanLimitGuard.LimitUsage("maxEmployees", 4, null, null, false, false),
    new PlanLimitGuard.LimitUsage("maxBranches", 2, null, null, false, false)));
  when(plans.findByStatusOrderByNameAsc(PlanStatus.active)).thenReturn(List.of(plan));
  when(invoices.findByTenantIdAndSubscriptionIdOrderByCycleStartDesc(tenantId, subscription.getId())).thenReturn(List.of());
 }

 @Test void currentUsesTenantLockBeforeSubscriptionAndReturnsFrontendContract() {
  stubSnapshot();
  var response = service.getCurrent();
  var order = inOrder(tenants, subscriptions, invoices);
  order.verify(tenants).findByIdForUpdate(tenantId);
  order.verify(subscriptions).findCurrentByTenantIdForUpdate(eq(tenantId), anyCollection());
  order.verify(invoices).findByTenantIdAndSubscriptionIdAndCycleStart(tenantId, subscription.getId(), Instant.parse("2025-02-28T00:00:00Z"));
  assertThat(response.tenantId()).isEqualTo(tenantId);
  assertThat(response.nextRenewalAt()).isEqualTo(Instant.parse("2025-03-31T00:00:00Z"));
  assertThat(response.usage().getFirst().limit()).isNull();
  assertThat(response.subscription().status()).isEqualTo(TenantSubscriptionStatus.active);
  assertThat(response.capabilities()).anyMatch(value -> value.key().equals("pos") && value.included());
 }

 @Test void addonChangesPreserveOriginalCyclePriceAndGrantAccessImmediately() {
  stubSnapshot();
  when(ecommerce.findByTenantId(tenantId)).thenReturn(Optional.empty());
  var response = service.updateAddons(new UpdateSubscriptionAddonsRequest(List.of("ecommerce_delivery")));
  ArgumentCaptor<SubscriptionInvoice> captor = ArgumentCaptor.forClass(SubscriptionInvoice.class);
  verify(invoices).save(captor.capture());
  assertThat(captor.getValue().getAddonCodes()).isEmpty();
  assertThat(captor.getValue().getTotalQuetzales()).isEqualByComparingTo("199.00");
  assertThat(response.addonCodes()).containsExactly("ecommerce_delivery");
  assertThat(response.capabilities()).anyMatch(value -> value.key().equals("ecommerce") && value.included() && value.operationalStatus().equals("Tienda desactivada"));
  var order = inOrder(invoices, subscriptions);
  order.verify(invoices).save(any());
  order.verify(subscriptions).save(subscription);
 }

 @Test void repeatedReadsNeverRewritePersistedInvoice() {
  stubSnapshot();
  when(invoices.findByTenantIdAndSubscriptionIdAndCycleStart(any(), any(), any())).thenReturn(Optional.of(SubscriptionInvoice.builder().build()));
  service.getCurrent();
  verify(invoices, never()).save(any());
 }

 @Test void nextCycleSnapshotUsesAddonPrices() {
  stubSnapshot();
  subscription.setAddonCodes(List.of("advanced_reports", "ecommerce_delivery"));
  when(ecommerce.findByTenantId(tenantId)).thenReturn(Optional.empty());
  service.getCurrent();
  ArgumentCaptor<SubscriptionInvoice> captor = ArgumentCaptor.forClass(SubscriptionInvoice.class);
  verify(invoices).save(captor.capture());
  assertThat(captor.getValue().getTotalQuetzales()).isEqualByComparingTo("427.00");
  assertThat(captor.getValue().getAddonLinesJson()).contains("129.00", "99.00");
 }

 @Test void suspendedSubscriptionRemainsSuspendedAndDoesNotProduceInvoice() {
  stubSnapshot();
  subscription.setStatus(TenantSubscriptionStatus.suspended);
  var response = service.updateAddons(new UpdateSubscriptionAddonsRequest(List.of("advanced_reports")));
  assertThat(response.subscription().status()).isEqualTo(TenantSubscriptionStatus.suspended);
  assertThat(response.capabilities()).anyMatch(value -> value.key().equals("reports.advanced") && value.included());
  verify(invoices, never()).save(any());
 }

 @Test void cancelledHistoryCanStillBeReadWithoutReactivation() {
  stubSnapshot(Optional.empty());
  subscription.setStatus(TenantSubscriptionStatus.cancelled);
  when(subscriptions.findFirstByTenantIdOrderByStartedAtDescCreatedAtDesc(tenantId)).thenReturn(Optional.of(subscription));
  assertThat(service.getCurrent().subscription().status()).isEqualTo(TenantSubscriptionStatus.cancelled);
  verify(invoices, never()).save(any());
 }

 @Test void missingSubscriptionCannotReadOtherTenantData() {
  when(tenants.findByIdForUpdate(tenantId)).thenReturn(Optional.of(Tenant.builder().build()));
  when(subscriptions.findCurrentByTenantIdForUpdate(eq(tenantId), anyCollection())).thenReturn(Optional.empty());
  when(subscriptions.findFirstByTenantIdOrderByStartedAtDescCreatedAtDesc(tenantId)).thenReturn(Optional.empty());
  assertThatThrownBy(() -> service.getCurrent()).isInstanceOfSatisfying(BusinessException.class,
    error -> assertThat(error.getCode()).isEqualTo("TENANT_SUBSCRIPTION_NOT_FOUND"));
  verifyNoInteractions(plans, invoices, limits);
 }

 @Test void invalidAddonDoesNotLockOrPersist() {
  assertThatThrownBy(() -> service.updateAddons(new UpdateSubscriptionAddonsRequest(List.of("unknown")))).isInstanceOf(BusinessException.class);
  verifyNoInteractions(tenants, subscriptions, invoices);
 }

 @Test void archivedPlanRejectsChangesBeforeInvoiceOrSubscriptionMutation() {
  when(tenants.findByIdForUpdate(tenantId)).thenReturn(Optional.of(Tenant.builder().build()));
  when(subscriptions.findCurrentByTenantIdForUpdate(eq(tenantId), anyCollection())).thenReturn(Optional.of(subscription));
  plan.setStatus(PlanStatus.archived);
  when(plans.findById(planId)).thenReturn(Optional.of(plan));
  assertThatThrownBy(() -> service.updateAddons(new UpdateSubscriptionAddonsRequest(List.of("advanced_reports"))))
    .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo("SAAS_PLAN_ARCHIVED"));
  verifyNoInteractions(invoices);
  verify(subscriptions, never()).save(any());
 }
}

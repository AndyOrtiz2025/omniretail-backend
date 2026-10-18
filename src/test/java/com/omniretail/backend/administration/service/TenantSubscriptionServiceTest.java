package com.omniretail.backend.administration.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
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

 /** Lo que necesita armar la respuesta (lectura y escritura). */
 void stubResponse() {
  when(plans.findById(planId)).thenReturn(Optional.of(plan));
  when(limits.evaluateUsage(tenantId, plan)).thenReturn(new PlanLimitGuard.PlanUsage(
    new PlanLimitGuard.LimitUsage("maxEmployees", 4, null, null, false, false),
    new PlanLimitGuard.LimitUsage("maxBranches", 2, null, null, false, false)));
  when(plans.findByStatusOrderByNameAsc(PlanStatus.active)).thenReturn(List.of(plan));
  when(invoices.findByTenantIdAndSubscriptionIdOrderByCycleStartDesc(tenantId, subscription.getId())).thenReturn(List.of());
 }

 /** Consulta informativa: la suscripcion se lee sin bloqueo. */
 void stubRead(Optional<TenantSubscription> current) {
  when(subscriptions.findByTenantIdAndStatusIn(eq(tenantId), anyCollection())).thenReturn(current);
  stubResponse();
 }

 /** Aprovisionamiento interno: tenant y suscripcion bloqueados. */
 void stubLocked() {
  when(tenants.findByIdForUpdate(tenantId)).thenReturn(Optional.of(Tenant.builder().build()));
  when(subscriptions.findCurrentByTenantIdForUpdate(eq(tenantId), anyCollection())).thenReturn(Optional.of(subscription));
  stubResponse();
 }

 @Test void currentIsReadOnlyAndReturnsFrontendContract() {
  stubRead(Optional.of(subscription));
  var response = service.getCurrent();
  verifyNoInteractions(tenants);
  verify(subscriptions, never()).findCurrentByTenantIdForUpdate(any(), anyCollection());
  verify(subscriptions, never()).save(any());
  verify(invoices, never()).save(any());
  verify(invoices, never()).findByTenantIdAndSubscriptionIdAndCycleStart(any(), any(), any());
  assertThat(response.tenantId()).isEqualTo(tenantId);
  assertThat(response.nextRenewalAt()).isEqualTo(Instant.parse("2025-03-31T00:00:00Z"));
  assertThat(response.usage().getFirst().limit()).isNull();
  assertThat(response.subscription().status()).isEqualTo(TenantSubscriptionStatus.active);
  assertThat(response.capabilities()).anyMatch(value -> value.key().equals("pos") && value.included());
 }

 @Test void currentNeverCreatesInvoicesEvenWithContractedAddonsAndRepeatedReads() {
  subscription.setAddonCodes(List.of("advanced_reports", "ecommerce_delivery"));
  when(ecommerce.findByTenantId(tenantId)).thenReturn(Optional.empty());
  stubRead(Optional.of(subscription));
  var first = service.getCurrent();
  var second = service.getCurrent();
  verify(invoices, never()).save(any());
  assertThat(first.addonCodes()).containsExactly("advanced_reports", "ecommerce_delivery");
  assertThat(second.capabilities()).anyMatch(value -> value.key().equals("reports.advanced") && value.included());
 }

 @Test void cancelledHistoryCanStillBeReadWithoutReactivation() {
  subscription.setStatus(TenantSubscriptionStatus.cancelled);
  when(subscriptions.findFirstByTenantIdOrderByStartedAtDescCreatedAtDesc(tenantId)).thenReturn(Optional.of(subscription));
  stubRead(Optional.empty());
  assertThat(service.getCurrent().subscription().status()).isEqualTo(TenantSubscriptionStatus.cancelled);
  verify(invoices, never()).save(any());
 }

 @Test void missingSubscriptionCannotReadOtherTenantData() {
  when(subscriptions.findByTenantIdAndStatusIn(eq(tenantId), anyCollection())).thenReturn(Optional.empty());
  when(subscriptions.findFirstByTenantIdOrderByStartedAtDescCreatedAtDesc(tenantId)).thenReturn(Optional.empty());
  assertThatThrownBy(() -> service.getCurrent()).isInstanceOfSatisfying(BusinessException.class,
    error -> assertThat(error.getCode()).isEqualTo("TENANT_SUBSCRIPTION_NOT_FOUND"));
  verifyNoInteractions(plans, invoices, limits, tenants);
 }

 @Test void addonChangesPreserveOriginalCyclePriceAndGrantAccessImmediately() {
  stubLocked();
  when(ecommerce.findByTenantId(tenantId)).thenReturn(Optional.empty());
  var response = service.configureAddons(tenantId, List.of("ecommerce_delivery"));
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

 @Test void configuringTargetsOnlyTheGivenTenantAndLocksTenantBeforeSubscription() {
  stubLocked();
  service.configureAddons(tenantId, List.of("advanced_reports"));
  var order = inOrder(tenants, subscriptions);
  order.verify(tenants).findByIdForUpdate(tenantId);
  order.verify(subscriptions).findCurrentByTenantIdForUpdate(eq(tenantId), anyCollection());
  verify(subscriptions, never()).findAll();
  verify(subscriptions, never()).saveAll(any());
 }

 @Test void nextCycleSnapshotUsesContractedAddonPrices() {
  subscription.setAddonCodes(List.of("advanced_reports", "ecommerce_delivery"));
  stubLocked();
  when(ecommerce.findByTenantId(tenantId)).thenReturn(Optional.empty());
  service.configureAddons(tenantId, List.of("advanced_reports", "ecommerce_delivery"));
  ArgumentCaptor<SubscriptionInvoice> captor = ArgumentCaptor.forClass(SubscriptionInvoice.class);
  verify(invoices).save(captor.capture());
  assertThat(captor.getValue().getTotalQuetzales()).isEqualByComparingTo("427.00");
  assertThat(captor.getValue().getAddonLinesJson()).contains("129.00", "99.00");
  verify(subscriptions, never()).save(any());
 }

 @Test void suspendedSubscriptionRemainsSuspendedAndDoesNotProduceInvoice() {
  subscription.setStatus(TenantSubscriptionStatus.suspended);
  stubLocked();
  var response = service.configureAddons(tenantId, List.of("advanced_reports"));
  assertThat(response.subscription().status()).isEqualTo(TenantSubscriptionStatus.suspended);
  assertThat(response.capabilities()).anyMatch(value -> value.key().equals("reports.advanced") && value.included());
  verify(invoices, never()).save(any());
 }

 @Test void invalidAddonDoesNotLockOrPersist() {
  assertThatThrownBy(() -> service.configureAddons(tenantId, List.of("unknown"))).isInstanceOf(BusinessException.class);
  verifyNoInteractions(tenants, subscriptions, invoices);
 }

 @Test void archivedPlanRejectsChangesBeforeInvoiceOrSubscriptionMutation() {
  when(tenants.findByIdForUpdate(tenantId)).thenReturn(Optional.of(Tenant.builder().build()));
  when(subscriptions.findCurrentByTenantIdForUpdate(eq(tenantId), anyCollection())).thenReturn(Optional.of(subscription));
  plan.setStatus(PlanStatus.archived);
  when(plans.findById(planId)).thenReturn(Optional.of(plan));
  assertThatThrownBy(() -> service.configureAddons(tenantId, List.of("advanced_reports")))
    .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo("SAAS_PLAN_ARCHIVED"));
  verifyNoInteractions(invoices);
  verify(subscriptions, never()).save(any());
 }
}

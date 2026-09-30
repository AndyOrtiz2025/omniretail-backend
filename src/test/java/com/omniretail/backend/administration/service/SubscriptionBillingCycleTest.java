package com.omniretail.backend.administration.service;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.omniretail.backend.shared.exception.BusinessException;
class SubscriptionBillingCycleTest {
 @Test void anchorDoesNotDriftAfterFebruary() {
  Instant anchor = Instant.parse("2025-01-31T00:00:00Z");
  var feb = SubscriptionBillingCycle.at(anchor, Instant.parse("2025-02-28T00:00:00Z"));
  assertThat(feb.start()).isEqualTo(Instant.parse("2025-02-28T00:00:00Z"));
  assertThat(feb.end()).isEqualTo(Instant.parse("2025-03-31T00:00:00Z"));
  assertThat(SubscriptionBillingCycle.at(anchor, Instant.parse("2025-03-30T23:59:59Z")).start()).isEqualTo(feb.start());
 }
 @Test void leapYearAndDecemberAreClampedInUtc() {
  var cycle = SubscriptionBillingCycle.at(Instant.parse("2023-12-31T12:00:00Z"), Instant.parse("2024-02-29T12:00:00Z"));
  assertThat(cycle.start()).isEqualTo(Instant.parse("2024-02-29T00:00:00Z"));
  assertThat(cycle.end()).isEqualTo(Instant.parse("2024-03-31T00:00:00Z"));
 }
 @Test void addonWhitelistRejectsUnknownOrDuplicateCodes() {
  assertThatThrownBy(() -> SubscriptionAddonCatalog.normalize(List.of("unknown"))).isInstanceOf(BusinessException.class);
  assertThatThrownBy(() -> SubscriptionAddonCatalog.normalize(List.of("advanced_reports", "advanced_reports"))).isInstanceOf(BusinessException.class);
  assertThat(SubscriptionAddonCatalog.normalize(List.of("ecommerce_delivery", "advanced_reports"))).containsExactly("advanced_reports", "ecommerce_delivery");
  assertThat(SubscriptionAddonCatalog.capabilities(List.of("ecommerce_delivery"))).containsExactly("ecommerce", "delivery");
 }
}

package com.omniretail.backend.administration.dto;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.omniretail.backend.administration.entity.TenantSubscriptionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TenantSubscriptionDetailsResponse(
 UUID tenantId, List<String> addonCodes, Instant nextRenewalAt, List<Invoice> invoices,
 Subscription subscription, Plan plan, List<Capability> capabilities, List<Usage> usage, List<SelectablePlan> availablePlans) {
 public record Subscription(TenantSubscriptionStatus status, Instant startedAt) {}
 public record Plan(UUID id, String code, String name, String description) {}
 public record SelectablePlan(UUID id, String code, String name, String description, List<String> capabilities) {}
 public record Usage(String key, long current, Integer limit) {}
 public record Capability(String key, boolean included, @JsonInclude(JsonInclude.Include.NON_NULL) String operationalStatus) {}
 public record AddonLine(String code, String name, BigDecimal amountQuetzales) {}
 public record Invoice(UUID id, UUID tenantId, Instant cycleStart, Instant cycleEnd, Instant createdAt,
   List<String> addonCodes, BigDecimal baseQuetzales, List<AddonLine> addonLines, BigDecimal totalQuetzales, String status) {}
}

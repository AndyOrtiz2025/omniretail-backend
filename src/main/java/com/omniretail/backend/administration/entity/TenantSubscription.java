package com.omniretail.backend.administration.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "tenant_subscriptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantSubscription extends TenantScopedEntity {

    @NotNull
    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TenantSubscriptionStatus status;

    @NotNull
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @NotNull
    @Column(name = "current_period_start", nullable = false)
    private Instant currentPeriodStart;

    @NotNull
    @Column(name = "current_period_end", nullable = false)
    private Instant currentPeriodEnd;

    @NotNull
    @Builder.Default
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "addon_codes", nullable = false, columnDefinition = "text[]")
    private List<String> addonCodes = List.of();
}

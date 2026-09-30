package com.omniretail.backend.administration.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "subscription_invoices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionInvoice extends TenantScopedEntity {
    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;
    @Column(name = "cycle_start", nullable = false)
    private Instant cycleStart;
    @Column(name = "cycle_end", nullable = false)
    private Instant cycleEnd;
    @Builder.Default
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "addon_codes", nullable = false, columnDefinition = "text[]")
    private List<String> addonCodes = List.of();
    @Column(name = "base_quetzales", nullable = false, precision = 12, scale = 2)
    private BigDecimal baseQuetzales;
    @Column(name = "total_quetzales", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalQuetzales;
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "addon_lines", nullable = false, columnDefinition = "jsonb")
    private String addonLinesJson = "[]";
    @Builder.Default
    @Column(name = "status", nullable = false, length = 20)
    private String status = "simulated";
}

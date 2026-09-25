package com.omniretail.backend.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * No extiende {@link com.omniretail.backend.shared.persistence.BaseEntity}: la tabla {@code
 * unit_conversions} no tiene columna {@code updated_at}, por lo que declara sus propias columnas
 * id/created_at.
 */
@Entity
@Table(name = "unit_conversions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UnitConversion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "product_id")
    private UUID productId;

    @NotNull
    @Column(name = "from_unit_id", nullable = false)
    private UUID fromUnitId;

    @NotNull
    @Column(name = "to_unit_id", nullable = false)
    private UUID toUnitId;

    @NotNull
    @Column(name = "factor", nullable = false, precision = 18, scale = 6)
    private BigDecimal factor;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

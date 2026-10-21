package com.omniretail.backend.administration.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Tarifa fija de envío a domicilio por canal, con umbral opcional de envío gratis. */
@Entity
@Table(name = "shipping_configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShippingConfig extends TenantScopedEntity {

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false)
    private ShippingChannel channel;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @NotNull
    @DecimalMin("0.00")
    @Column(name = "delivery_fee", nullable = false, precision = 12, scale = 2)
    private BigDecimal deliveryFee;

    @DecimalMin("0.00")
    @Column(name = "free_shipping_threshold", precision = 12, scale = 2)
    private BigDecimal freeShippingThreshold;
}

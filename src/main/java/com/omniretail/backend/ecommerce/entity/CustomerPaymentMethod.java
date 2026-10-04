package com.omniretail.backend.ecommerce.entity;

import com.omniretail.backend.pos.entity.PaymentMethod;
import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Tarjeta guardada del cliente (CustomerPaymentMethod.ts). Solo datos no sensibles: nunca el numero
 * completo ni el CVV. {@code providerPaymentMethodId} es el token simulado que genera el servidor.
 */
@Entity
@Table(name = "customer_payment_methods")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerPaymentMethod extends TenantScopedEntity {

    @NotNull
    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @NotNull
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false)
    private PaymentMethod type = PaymentMethod.card;

    @NotBlank
    @Size(max = 100)
    @Column(name = "provider_payment_method_id", nullable = false, updatable = false)
    private String providerPaymentMethodId;

    @NotBlank
    @Size(max = 40)
    @Column(name = "brand", nullable = false, updatable = false)
    private String brand;

    @NotBlank
    @Size(max = 80)
    @Column(name = "issuing_bank", nullable = false, updatable = false)
    private String issuingBank;

    @NotBlank
    @Size(min = 4, max = 4)
    @Column(name = "last4", nullable = false, updatable = false)
    private String last4;

    @NotNull
    @Column(name = "expiration_month", nullable = false)
    private Integer expirationMonth;

    @NotNull
    @Column(name = "expiration_year", nullable = false)
    private Integer expirationYear;

    @Size(max = 60)
    @Column(name = "cardholder_name")
    private String cardholderName;

    @NotNull
    @Builder.Default
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;
}

package com.omniretail.backend.ecommerce.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Pedido inmutable en sus snapshots; sus estados sí evolucionan durante el cumplimiento. */
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order extends TenantScopedEntity {

    @NotNull
    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @NotBlank
    @Size(max = 50)
    @Column(name = "order_number", nullable = false, updatable = false)
    private String orderNumber;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false)
    private OrderSource source;

    @Column(name = "customer_id", updatable = false)
    private UUID customerId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "guest_customer", columnDefinition = "jsonb", updatable = false)
    private String guestCustomer;

    @NotNull
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OrderStatus status = OrderStatus.pending;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_method", nullable = false, updatable = false)
    private DeliveryMethod deliveryMethod;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "transport_mode", nullable = false, updatable = false)
    private TransportMode transportMode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "delivery_address", columnDefinition = "jsonb", updatable = false)
    private String deliveryAddress;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "notification_contact", columnDefinition = "jsonb", updatable = false)
    private String notificationContact;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "store_pickup_contact", columnDefinition = "jsonb", updatable = false)
    private String storePickupContact;

    @NotNull
    @DecimalMin("0.00")
    @Column(name = "subtotal", nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @NotNull
    @DecimalMin("0.00")
    @Column(
            name = "discount_total",
            nullable = false,
            updatable = false,
            precision = 12,
            scale = 2)
    private BigDecimal discountTotal;

    @NotNull
    @DecimalMin("0.00")
    @Column(
            name = "shipping_total",
            nullable = false,
            updatable = false,
            precision = 12,
            scale = 2)
    private BigDecimal shippingTotal;

    @NotNull
    @DecimalMin("0.00")
    @Column(name = "total", nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal total;

    @NotBlank
    @Size(max = 128)
    @Column(name = "tracking_token", nullable = false, updatable = false)
    private String trackingToken;

    @Size(max = 128)
    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "idempotency_fingerprint", columnDefinition = "TEXT", updatable = false)
    private String idempotencyFingerprint;
}

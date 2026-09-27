package com.omniretail.backend.administration.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
@Table(name = "ecommerce_configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EcommerceConfig extends TenantScopedEntity {

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @NotBlank
    @Size(max = 120)
    @Column(name = "store_name", nullable = false)
    private String storeName;

    @Size(max = 500)
    @Column(name = "logo_url")
    private String logoUrl;

    @Size(max = 20)
    @Column(name = "contact_phone")
    private String contactPhone;

    @Email
    @Size(max = 254)
    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "require_account_for_checkout", nullable = false)
    private boolean requireAccountForCheckout;

    @Column(name = "guest_tracking_enabled", nullable = false)
    private boolean guestTrackingEnabled;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_delivery_methods", columnDefinition = "varchar(30)[]")
    private List<String> allowedDeliveryMethods;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_payment_methods", columnDefinition = "varchar(30)[]")
    private List<String> allowedPaymentMethods;

    @Column(name = "default_branch_id")
    private UUID defaultBranchId;
}

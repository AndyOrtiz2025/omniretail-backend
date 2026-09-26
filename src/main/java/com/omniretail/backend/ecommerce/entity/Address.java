package com.omniretail.backend.ecommerce.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

@Entity
@Table(name = "addresses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Address extends TenantScopedEntity {

    @NotNull
    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @NotBlank
    @Size(max = 35)
    @Column(name = "label", nullable = false)
    private String label;

    @NotBlank
    @Size(max = 60)
    @Column(name = "recipient_name", nullable = false)
    private String recipientName;

    @NotBlank
    @Size(max = 200)
    @Column(name = "line1", nullable = false)
    private String line1;

    @Size(max = 200)
    @Column(name = "line2")
    private String line2;

    @NotBlank
    @Size(max = 100)
    @Column(name = "city", nullable = false)
    private String city;

    @Size(max = 100)
    @Column(name = "state_or_department")
    private String stateOrDepartment;

    @Size(max = 20)
    @Column(name = "postal_code")
    private String postalCode;

    @NotBlank
    @Size(max = 100)
    @Column(name = "country", nullable = false)
    private String country;

    @Size(max = 300)
    @Column(name = "reference_notes")
    private String references;

    @NotNull
    @Builder.Default
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;
}

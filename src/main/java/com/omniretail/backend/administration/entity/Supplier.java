package com.omniretail.backend.administration.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "suppliers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Supplier extends TenantScopedEntity {

    @NotBlank
    @Size(max = 120)
    @Column(name = "name", nullable = false)
    private String name;

    @Size(max = 160)
    @Column(name = "legal_name")
    private String legalName;

    @Size(max = 20)
    @Column(name = "tax_id")
    private String taxId;

    @Email
    @Size(max = 254)
    @Column(name = "email")
    private String email;

    @Size(max = 20)
    @Column(name = "phone")
    private String phone;

    @Size(max = 180)
    @Column(name = "address")
    private String address;

    @Size(max = 500)
    @Column(name = "notes")
    private String notes;

    @Builder.Default
    @Column(name = "lead_time_days")
    private Integer leadTimeDays = 0;

    @NotNull
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SupplierStatus status = SupplierStatus.active;
}

package com.omniretail.backend.administration.entity;

import com.omniretail.backend.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "tenants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tenant extends BaseEntity {

    @NotBlank
    @Size(max = 200)
    @Column(name = "name", nullable = false)
    private String name;

    @Size(max = 300)
    @Column(name = "legal_name")
    private String legalName;

    @NotBlank
    @Size(max = 100)
    @Column(name = "slug", nullable = false, unique = true)
    private String slug;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TenantStatus status;

    @NotBlank
    @Size(max = 3)
    @Column(name = "default_currency", nullable = false)
    private String defaultCurrency;

    @NotBlank
    @Size(max = 50)
    @Column(name = "timezone", nullable = false)
    private String timezone;
}

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
@Table(name = "branches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Branch extends TenantScopedEntity {

    @NotBlank
    @Size(max = 50)
    @Column(name = "code", nullable = false)
    private String code;

    @NotBlank
    @Size(max = 200)
    @Column(name = "name", nullable = false)
    private String name;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private BranchType type;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    @Size(max = 20)
    @Column(name = "phone")
    private String phone;

    @Email
    @Size(max = 200)
    @Column(name = "email")
    private String email;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BranchStatus status;
}

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
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User extends TenantScopedEntity {

    @Column(name = "customer_id")
    private UUID customerId;

    @Size(max = 50)
    @Column(name = "employee_code")
    private String employeeCode;

    @NotBlank
    @Size(max = 200)
    @Column(name = "name", nullable = false)
    private String name;

    @NotBlank
    @Email
    @Size(max = 200)
    @Column(name = "email", nullable = false)
    private String email;

    @Size(max = 20)
    @Column(name = "phone")
    private String phone;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private UserType type;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private UserStatus status;

    @Column(name = "role_id")
    private UUID roleId;

    @Column(name = "branch_id")
    private UUID branchId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_branch_ids", columnDefinition = "uuid[]")
    private List<UUID> allowedBranchIds;
}

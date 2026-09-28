package com.omniretail.backend.catalog.entity;

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

@Entity
@Table(name = "locations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Location extends TenantScopedEntity {

    @NotNull
    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "parent_id", updatable = false)
    private UUID parentId;

    @NotBlank
    @Size(max = 50)
    @Column(name = "code", nullable = false, updatable = false, length = 50)
    private String code;

    @NotBlank
    @Size(max = 100)
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 20)
    private LocationType type;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LocationStatus status;
}

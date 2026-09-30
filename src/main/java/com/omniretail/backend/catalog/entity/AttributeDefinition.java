package com.omniretail.backend.catalog.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "attribute_definitions")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AttributeDefinition extends TenantScopedEntity {

    @Column(name = "code", nullable = false, length = 50, updatable = false)
    private String code;

    @Setter
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_type", nullable = false, length = 20, updatable = false)
    private AttributeDataType dataType;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AttributeDefinitionStatus status;
}

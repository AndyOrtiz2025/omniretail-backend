package com.omniretail.backend.catalog.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "product_attribute_values")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductAttributeValue extends TenantScopedEntity {

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "attribute_definition_id", nullable = false, updatable = false)
    private UUID attributeDefinitionId;

    @Column(name = "value_string", nullable = false, length = 500, updatable = false)
    private String valueString;
}

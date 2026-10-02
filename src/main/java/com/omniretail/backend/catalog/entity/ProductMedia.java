package com.omniretail.backend.catalog.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "product_media")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ProductMedia extends TenantScopedEntity {
    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private ProductMediaType type;
    @Column(name = "url", nullable = false, columnDefinition = "TEXT")
    private String url;
    @Column(name = "alt_text", columnDefinition = "TEXT")
    private String altText;
    @Builder.Default
    @Column(name = "is_primary", nullable = false)
    private Boolean primary = false;
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}

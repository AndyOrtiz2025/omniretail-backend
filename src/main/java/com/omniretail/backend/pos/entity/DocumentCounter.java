package com.omniretail.backend.pos.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "document_counters")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentCounter extends TenantScopedEntity {

    @NotBlank
    @Size(max = 50)
    @Column(name = "counter_key", nullable = false)
    private String counterKey;

    @NotNull
    @Builder.Default
    @Column(name = "last_value", nullable = false)
    private Long lastValue = 0L;
}

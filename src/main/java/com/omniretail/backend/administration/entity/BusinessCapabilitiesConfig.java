package com.omniretail.backend.administration.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "business_capabilities_configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BusinessCapabilitiesConfig extends TenantScopedEntity {

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "preset", nullable = false)
    private BusinessPreset preset;

    @Column(name = "supports_inventory", nullable = false)
    private boolean supportsInventory;

    @Column(name = "supports_lots", nullable = false)
    private boolean supportsLots;

    @Column(name = "supports_expiration", nullable = false)
    private boolean supportsExpiration;

    @Column(name = "supports_serials", nullable = false)
    private boolean supportsSerials;

    @Column(name = "supports_multiple_locations", nullable = false)
    private boolean supportsMultipleLocations;

    @Column(name = "supports_units_and_packaging", nullable = false)
    private boolean supportsUnitsAndPackaging;

    @Column(name = "supports_product_attributes", nullable = false)
    private boolean supportsProductAttributes;

    @Column(name = "supports_kits", nullable = false)
    private boolean supportsKits;

    @Column(name = "supports_services", nullable = false)
    private boolean supportsServices;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_pos_payment_methods", columnDefinition = "text[]")
    private List<String> allowedPosPaymentMethods;

    @Column(name = "track_stock", nullable = false)
    private boolean trackStock;

    @Column(name = "track_lot", nullable = false)
    private boolean trackLot;

    @Column(name = "track_expiration", nullable = false)
    private boolean trackExpiration;

    @Column(name = "track_serial", nullable = false)
    private boolean trackSerial;
}

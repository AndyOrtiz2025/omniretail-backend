package com.omniretail.backend.catalog.repository;

import com.omniretail.backend.catalog.entity.UnitConversion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UnitConversionRepository extends JpaRepository<UnitConversion, UUID> {

    List<UnitConversion> findByTenantIdAndProductId(UUID tenantId, UUID productId);

    Optional<UnitConversion> findByTenantIdAndFromUnitIdAndToUnitIdAndProductIdIsNull(
            UUID tenantId, UUID fromUnitId, UUID toUnitId);
}

package com.omniretail.backend.catalog.repository;

import com.omniretail.backend.catalog.entity.UnitConversion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UnitConversionRepository extends JpaRepository<UnitConversion, UUID> {

    Optional<UnitConversion> findByTenantIdAndId(UUID tenantId, UUID id);

    List<UnitConversion> findByTenantIdAndProductId(UUID tenantId, UUID productId);

    Optional<UnitConversion> findByTenantIdAndFromUnitIdAndToUnitIdAndProductIdIsNull(
            UUID tenantId, UUID fromUnitId, UUID toUnitId);

    boolean existsByTenantIdAndProductIdIsNullAndFromUnitIdAndToUnitId(
            UUID tenantId, UUID fromUnitId, UUID toUnitId);

    boolean existsByTenantIdAndProductIdAndFromUnitIdAndToUnitId(
            UUID tenantId, UUID productId, UUID fromUnitId, UUID toUnitId);

    boolean existsByTenantIdAndProductId(UUID tenantId, UUID productId);

    void deleteByTenantIdAndProductId(UUID tenantId, UUID productId);

    @Query("""
            SELECT conversion
            FROM UnitConversion conversion
            WHERE conversion.tenantId = :tenantId
              AND (:productId IS NULL OR conversion.productId = :productId)
              AND (:fromUnitId IS NULL OR conversion.fromUnitId = :fromUnitId)
              AND (:toUnitId IS NULL OR conversion.toUnitId = :toUnitId)
            """)
    Page<UnitConversion> findAllFiltered(
            @Param("tenantId") UUID tenantId,
            @Param("productId") UUID productId,
            @Param("fromUnitId") UUID fromUnitId,
            @Param("toUnitId") UUID toUnitId,
            Pageable pageable);
}

package com.omniretail.backend.catalog.repository;

import com.omniretail.backend.catalog.entity.Location;
import com.omniretail.backend.catalog.entity.LocationStatus;
import com.omniretail.backend.catalog.entity.LocationType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LocationRepository extends JpaRepository<Location, UUID> {

    Optional<Location> findByTenantIdAndId(UUID tenantId, UUID id);

    List<Location> findByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);

    boolean existsByTenantIdAndBranchIdAndCode(
            UUID tenantId, UUID branchId, String code);

    boolean existsByTenantIdAndParentIdAndStatus(
            UUID tenantId, UUID parentId, LocationStatus status);

    @Query("""
            SELECT location
            FROM Location location
            WHERE location.tenantId = :tenantId
              AND (:branchId IS NULL OR location.branchId = :branchId)
              AND (:parentId IS NULL OR location.parentId = :parentId)
              AND (:type IS NULL OR location.type = :type)
              AND (:status IS NULL OR location.status = :status)
            """)
    Page<Location> findAllFiltered(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("parentId") UUID parentId,
            @Param("type") LocationType type,
            @Param("status") LocationStatus status,
            Pageable pageable);

    @Query("""
            SELECT location
            FROM Location location
            WHERE location.tenantId = :tenantId
              AND location.branchId IN (:allowedBranchIds)
              AND (:parentId IS NULL OR location.parentId = :parentId)
              AND (:type IS NULL OR location.type = :type)
              AND (:status IS NULL OR location.status = :status)
            """)
    Page<Location> findAllFilteredForBranches(
            @Param("tenantId") UUID tenantId,
            @Param("allowedBranchIds") Collection<UUID> allowedBranchIds,
            @Param("parentId") UUID parentId,
            @Param("type") LocationType type,
            @Param("status") LocationStatus status,
            Pageable pageable);
}

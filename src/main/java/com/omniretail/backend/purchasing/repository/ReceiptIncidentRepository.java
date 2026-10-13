package com.omniretail.backend.purchasing.repository;

import com.omniretail.backend.purchasing.dto.ReceiptIncidentDashboardRow;
import com.omniretail.backend.purchasing.entity.ReceiptIncident;
import com.omniretail.backend.purchasing.entity.ReceiptIncidentStatus;
import com.omniretail.backend.purchasing.entity.ReceiptIncidentType;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReceiptIncidentRepository extends JpaRepository<ReceiptIncident, UUID> {

    Optional<ReceiptIncident> findByTenantIdAndId(UUID tenantId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select incident from ReceiptIncident incident where incident.tenantId = :tenantId and incident.id = :id")
    Optional<ReceiptIncident> findForUpdateByTenantIdAndId(
            @Param("tenantId") UUID tenantId, @Param("id") UUID id);

    Page<ReceiptIncident> findByTenantIdAndGoodsReceiptId(
            UUID tenantId, UUID goodsReceiptId, Pageable pageable);

    /** Total de incidencias (abiertas y resueltas) por recepción, en una sola query agrupada. */
    @Query("""
            select incident.goodsReceiptId as goodsReceiptId, count(incident) as total
            from ReceiptIncident incident
            where incident.tenantId = :tenantId and incident.goodsReceiptId in :goodsReceiptIds
            group by incident.goodsReceiptId
            """)
    List<ReceiptIncidentCount> countByGoodsReceiptIds(
            @Param("tenantId") UUID tenantId, @Param("goodsReceiptIds") Collection<UUID> goodsReceiptIds);

    interface ReceiptIncidentCount {
        UUID getGoodsReceiptId();

        long getTotal();
    }

    boolean existsByTenantIdAndGoodsReceiptItemId(UUID tenantId, UUID goodsReceiptItemId);

    /** Conteo de incidencias agrupado por tipo, para el dashboard de administración. */
    interface DashboardTypeCount {
        ReceiptIncidentType getIncidentType();

        long getTotal();
    }

    /** Conteo de incidencias agrupado por proveedor, para el dashboard de administración. */
    interface DashboardSupplierCount {
        String getSupplierName();

        long getTotal();
    }

    @Query("""
            select coalesce(sum(incident.quantityAffected), 0) from ReceiptIncident incident
            where incident.tenantId = :tenantId
              and incident.goodsReceiptItemId = :goodsReceiptItemId
              and incident.status = :status
            """)
    BigDecimal sumQuantityAffectedByItemAndStatus(
            @Param("tenantId") UUID tenantId,
            @Param("goodsReceiptItemId") UUID goodsReceiptItemId,
            @Param("status") ReceiptIncidentStatus status);

    /**
     * Historial de incidencias de UN proveedor (incidencia -> recepción -> orden -> proveedor), con número
     * de recepción/orden y producto de la línea resueltos por join. Versión para todas las sucursales.
     */
    @Query(
            value = """
                    select new com.omniretail.backend.purchasing.dto.PurchasingSupplierIncidentResponse(
                        i.id, i.incidentType, i.status, i.quantityAffected, i.notes, i.createdAt, i.resolvedAt,
                        r.id, r.number, o.id, o.number, i.branchId, p.id, p.name)
                    from ReceiptIncident i
                    join GoodsReceipt r on r.id = i.goodsReceiptId and r.tenantId = :tenantId
                    join PurchaseOrder o on o.id = r.purchaseOrderId and o.tenantId = :tenantId
                    left join GoodsReceiptItem gi on gi.id = i.goodsReceiptItemId and gi.tenantId = :tenantId
                    left join Product p on p.id = gi.productId and p.tenantId = :tenantId
                    where i.tenantId = :tenantId
                      and o.supplierId = :supplierId
                      and (:branchId is null or i.branchId = :branchId)
                      and (:status is null or i.status = :status)
                    order by i.createdAt desc, i.id desc
                    """,
            countQuery = """
                    select count(i) from ReceiptIncident i
                    join GoodsReceipt r on r.id = i.goodsReceiptId and r.tenantId = :tenantId
                    join PurchaseOrder o on o.id = r.purchaseOrderId and o.tenantId = :tenantId
                    where i.tenantId = :tenantId
                      and o.supplierId = :supplierId
                      and (:branchId is null or i.branchId = :branchId)
                      and (:status is null or i.status = :status)
                    """)
    Page<com.omniretail.backend.purchasing.dto.PurchasingSupplierIncidentResponse> findSupplierHistory(
            @Param("tenantId") UUID tenantId,
            @Param("supplierId") UUID supplierId,
            @Param("branchId") UUID branchId,
            @Param("status") ReceiptIncidentStatus status,
            Pageable pageable);

    /** Igual que {@link #findSupplierHistory} pero limitado a las sucursales autorizadas del usuario. */
    @Query(
            value = """
                    select new com.omniretail.backend.purchasing.dto.PurchasingSupplierIncidentResponse(
                        i.id, i.incidentType, i.status, i.quantityAffected, i.notes, i.createdAt, i.resolvedAt,
                        r.id, r.number, o.id, o.number, i.branchId, p.id, p.name)
                    from ReceiptIncident i
                    join GoodsReceipt r on r.id = i.goodsReceiptId and r.tenantId = :tenantId
                    join PurchaseOrder o on o.id = r.purchaseOrderId and o.tenantId = :tenantId
                    left join GoodsReceiptItem gi on gi.id = i.goodsReceiptItemId and gi.tenantId = :tenantId
                    left join Product p on p.id = gi.productId and p.tenantId = :tenantId
                    where i.tenantId = :tenantId
                      and o.supplierId = :supplierId
                      and i.branchId in :allowedBranchIds
                      and (:branchId is null or i.branchId = :branchId)
                      and (:status is null or i.status = :status)
                    order by i.createdAt desc, i.id desc
                    """,
            countQuery = """
                    select count(i) from ReceiptIncident i
                    join GoodsReceipt r on r.id = i.goodsReceiptId and r.tenantId = :tenantId
                    join PurchaseOrder o on o.id = r.purchaseOrderId and o.tenantId = :tenantId
                    where i.tenantId = :tenantId
                      and o.supplierId = :supplierId
                      and i.branchId in :allowedBranchIds
                      and (:branchId is null or i.branchId = :branchId)
                      and (:status is null or i.status = :status)
                    """)
    Page<com.omniretail.backend.purchasing.dto.PurchasingSupplierIncidentResponse>
            findSupplierHistoryForBranches(
                    @Param("tenantId") UUID tenantId,
                    @Param("supplierId") UUID supplierId,
                    @Param("allowedBranchIds") java.util.Collection<UUID> allowedBranchIds,
                    @Param("branchId") UUID branchId,
                    @Param("status") ReceiptIncidentStatus status,
                    Pageable pageable);

    /**
     * Conteo de incidencias creadas en [monthStart, nextMonthStart) agrupado por tipo.
     * El total mensual del dashboard se obtiene sumando los conteos de cada tipo.
     */
    @Query("""
            select i.incidentType as incidentType, count(i) as total
            from ReceiptIncident i
            where i.tenantId = :tenantId
              and i.createdAt >= :monthStart
              and i.createdAt < :nextMonthStart
            group by i.incidentType
            """)
    List<DashboardTypeCount> countDashboardIncidentsByTypeCreatedBetween(
            @Param("tenantId") UUID tenantId,
            @Param("monthStart") Instant monthStart,
            @Param("nextMonthStart") Instant nextMonthStart);

    /**
     * Conteo de incidencias creadas en [monthStart, nextMonthStart) agrupado por proveedor (supplierId).
     * Usa el nombre actual del proveedor y cae a supplierNameSnapshot de la orden de compra si no está
     * disponible, sin alterar la agrupación por supplierId.
     */
    @Query("""
            select coalesce(max(s.name), max(o.supplierNameSnapshot)) as supplierName,
                   count(i) as total
            from ReceiptIncident i
            join GoodsReceipt r on r.id = i.goodsReceiptId and r.tenantId = :tenantId
            join PurchaseOrder o on o.id = r.purchaseOrderId and o.tenantId = :tenantId
            left join Supplier s on s.id = o.supplierId and s.tenantId = :tenantId
            where i.tenantId = :tenantId
              and i.createdAt >= :monthStart
              and i.createdAt < :nextMonthStart
              and o.supplierId is not null
              and coalesce(s.name, o.supplierNameSnapshot) is not null
            group by o.supplierId
            order by count(i) desc
            """)
    List<DashboardSupplierCount> countDashboardIncidentsBySupplierCreatedBetween(
            @Param("tenantId") UUID tenantId,
            @Param("monthStart") Instant monthStart,
            @Param("nextMonthStart") Instant nextMonthStart);

    /**
     * Últimas incidencias creadas en [monthStart, nextMonthStart), abiertas y resueltas, con recepción,
     * orden, proveedor (con fallback a supplierNameSnapshot) y sucursal resueltos en una sola query
     * ordenada y limitada mediante Pageable. Lo usa el dashboard de administración.
     */
    @Query("""
            select new com.omniretail.backend.purchasing.dto.ReceiptIncidentDashboardRow(
                i.incidentType, i.notes, i.createdAt,
                r.number, o.number, coalesce(s.name, o.supplierNameSnapshot), b.name)
            from ReceiptIncident i
            left join GoodsReceipt r on r.id = i.goodsReceiptId and r.tenantId = :tenantId
            left join PurchaseOrder o on o.id = r.purchaseOrderId and o.tenantId = :tenantId
            left join Supplier s on s.id = o.supplierId and s.tenantId = :tenantId
            left join Branch b on b.id = i.branchId and b.tenantId = :tenantId
            where i.tenantId = :tenantId
              and i.createdAt >= :monthStart
              and i.createdAt < :nextMonthStart
            order by i.createdAt desc, i.id desc
            """)
    List<ReceiptIncidentDashboardRow> findDashboardRowsCreatedBetween(
            @Param("tenantId") UUID tenantId,
            @Param("monthStart") Instant monthStart,
            @Param("nextMonthStart") Instant nextMonthStart,
            Pageable pageable);

    boolean existsByTenantIdAndGoodsReceiptIdAndStatus(
            UUID tenantId, UUID goodsReceiptId, ReceiptIncidentStatus status);
}

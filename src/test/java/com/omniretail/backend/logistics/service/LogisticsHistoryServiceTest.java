package com.omniretail.backend.logistics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.logistics.dto.LogisticsHistoryDeliveryMethod;
import com.omniretail.backend.logistics.dto.LogisticsHistoryDetailResponse;
import com.omniretail.backend.logistics.dto.LogisticsHistoryRowResponse;
import com.omniretail.backend.logistics.entity.PickingSourceType;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.time.LocalDate;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class LogisticsHistoryServiceTest {

    @Autowired private LogisticsHistoryService service;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;
    @MockitoBean private BranchAccessResolver branchAccessResolver;
    @MockitoBean private TenantCapabilityGuard capabilityGuard;

    private DispatchTestFixture.Data fixture;
    private UUID pickingId;

    @BeforeEach
    void setUp() {
        fixture = DispatchTestFixture.create(jdbc, 2);
        pickingId = jdbc.queryForObject(
                "SELECT id FROM picking_orders WHERE source_id = ?", UUID.class, fixture.orderId());
        insertPickingItem(
                pickingId,
                fixture.orderItemId(),
                fixture.productId(),
                fixture.locationId(),
                "5.000",
                null);
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                fixture.userId(),
                fixture.tenantId(),
                UserType.employee,
                null,
                fixture.branchId(),
                UUID.randomUUID()));
        given(branchAccessResolver.resolve(any()))
                .willReturn(new BranchAccess(true, Set.of()));
    }

    @Test
    void pagesOrdersStablyAndAppliesReferenceCustomerStatusDeliveryAndDateFilters() {
        jdbc.update(
                "UPDATE picking_orders SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(java.time.Instant.parse("2026-10-09T15:00:00Z")),
                pickingId);

        PageResponse<?> result = service.search(
                fixture.branchId(),
                "Ana Lopez",
                "ready_for_dispatch",
                "home_delivery",
                LocalDate.parse("2026-10-09"),
                LocalDate.parse("2026-10-09"),
                0,
                1);

        assertThat(result.items()).hasSize(1);
        assertThat(result.page()).isOne();
        assertThat(result.pageSize()).isOne();
        assertThat(result.totalItems()).isOne();
    }

    @Test
    void paginatesBeforeHydrationWithStableDescendingOrderAndCorrectTotals() {
        jdbc.update(
                "UPDATE picking_orders SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.parse("2026-10-08T12:00:00Z")),
                pickingId);
        simpleOrderWithPicking("WEB-NEWEST", Instant.parse("2026-10-09T12:00:00Z"));

        PageResponse<LogisticsHistoryRowResponse> first = service.search(
                fixture.branchId(), null, null, null, null, null, 0, 1);
        PageResponse<LogisticsHistoryRowResponse> second = service.search(
                fixture.branchId(), null, null, null, null, null, 1, 1);

        assertThat(first.items()).singleElement().satisfies(row ->
                assertThat(row.orderReference()).isEqualTo("WEB-NEWEST"));
        assertThat(second.items()).singleElement().satisfies(row ->
                assertThat(row.orderReference()).isEqualTo("WEB-" + fixture.orderId()));
        assertThat(first.totalItems()).isEqualTo(2);
        assertThat(first.totalPages()).isEqualTo(2);
    }

    @Test
    void searchesRegisteredCustomerNameAndKeepsItsDisplayPriority() {
        UUID customerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO customers
                    (id, tenant_id, code, name, email, phone, status)
                VALUES (?, ?, ?, 'Cliente Registrado', ?, '+50222223333', 'active')
                """,
                customerId,
                fixture.tenantId(),
                "CUS-" + customerId.toString().substring(0, 8),
                customerId + "@test.local");
        jdbc.update(
                "UPDATE orders SET customer_id = ?, guest_customer = null WHERE id = ?",
                customerId,
                fixture.orderId());

        PageResponse<?> page = service.search(
                fixture.branchId(), "Cliente Registrado", null, null, null, null, 0, 20);
        LogisticsHistoryDetailResponse detail = service.detail(
                fixture.branchId(), PickingSourceType.order, fixture.orderId());

        assertThat(page.totalItems()).isOne();
        assertThat(detail.summary().contactName()).isEqualTo("Cliente Registrado");
        assertThat(detail.summary().contactPhone()).isEqualTo("+502 5555-5555");
    }

    @Test
    void detailReturnsPickingPackingDerivedQuantitiesAndNoDispatchBeforeConfirmation() {
        LogisticsHistoryDetailResponse result = service.detail(
                fixture.branchId(), PickingSourceType.order, fixture.orderId());

        assertThat(result.summary().orderReference()).isEqualTo("WEB-" + fixture.orderId());
        assertThat(result.summary().contactName()).isEqualTo("Cliente");
        assertThat(result.summary().contactPhone()).isEqualTo("+502 5555-5555");
        assertThat(result.summary().packingId()).isEqualTo(fixture.packingId());
        assertThat(result.summary().dispatchId()).isNull();
        assertThat(result.summary().storePickupDeliveryId()).isNull();
        assertThat(result.lines()).singleElement().satisfies(line -> {
            assertThat(line.productName()).isEqualTo("Producto Dispatch");
            assertThat(line.requestedQuantity()).isEqualByComparingTo("5.000");
            assertThat(line.pickedQuantity()).isEqualByComparingTo("5.000");
            assertThat(line.packedQuantity()).isEqualByComparingTo("5.000");
            assertThat(line.dispatchedQuantity()).isNull();
        });
        assertThat(result.packages()).isEmpty();
    }

    @Test
    void orderWithOnlyPickingKeepsLaterStagesAndDerivedQuantitiesNull() {
        jdbc.update("DELETE FROM packings WHERE id = ?", fixture.packingId());

        LogisticsHistoryDetailResponse result = service.detail(
                fixture.branchId(), PickingSourceType.order, fixture.orderId());

        assertThat(result.summary().pickingOrderId()).isEqualTo(pickingId);
        assertThat(result.summary().packingId()).isNull();
        assertThat(result.summary().dispatchId()).isNull();
        assertThat(result.lines()).singleElement().satisfies(line -> {
            assertThat(line.packedQuantity()).isNull();
            assertThat(line.dispatchedQuantity()).isNull();
        });
    }

    @Test
    void dispatchedOrderDerivesDispatchedQuantityAndLoadsPackagesAndResponsibleUser() {
        UUID dispatchId = dispatch("delivered");
        jdbc.update("UPDATE orders SET status = 'delivered', delivered_at = now() WHERE id = ?",
                fixture.orderId());
        jdbc.update("""
                INSERT INTO dispatch_packages (id, dispatch_id, number, weight, description)
                VALUES (?, ?, 'PKG-1', 4.500, 'Caja')
                """, UUID.randomUUID(), dispatchId);

        LogisticsHistoryDetailResponse result = service.detail(
                fixture.branchId(), PickingSourceType.order, fixture.orderId());

        assertThat(result.summary().dispatchId()).isEqualTo(dispatchId);
        assertThat(result.summary().dispatchStatus().name()).isEqualTo("delivered");
        assertThat(result.summary().responsibleUserId()).isEqualTo(fixture.userId());
        assertThat(result.summary().responsibleUserName()).isEqualTo("Despachador");
        assertThat(result.lines()).singleElement().satisfies(line ->
                assertThat(line.dispatchedQuantity()).isEqualByComparingTo("5.000"));
        assertThat(result.packages()).singleElement().satisfies(value ->
                assertThat(value.number()).isEqualTo("PKG-1"));
    }

    @Test
    void cancelledOrderRemainsVisibleAndReadsDoNotMutateOperationalState() {
        jdbc.update("UPDATE orders SET status = 'cancelled' WHERE id = ?", fixture.orderId());
        jdbc.update("UPDATE picking_orders SET status = 'cancelled', completed_at = null WHERE id = ?",
                pickingId);

        PageResponse<?> result = service.search(
                fixture.branchId(), null, "cancelled", null, null, null, 0, 20);

        assertThat(result.items()).hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, fixture.orderId()))
                .isEqualTo("cancelled");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM picking_orders WHERE id = ?", String.class, pickingId))
                .isEqualTo("cancelled");
    }

    @Test
    void deliveredStorePickupUsesRecipientAndHasNoSyntheticDeliveryId() {
        jdbc.update("""
                UPDATE orders
                SET source = 'pos', status = 'delivered', delivery_method = 'store_pickup',
                    delivered_at = now(),
                    store_pickup_contact =
                      '{"recipientName":"Persona Retiro","recipientPhone":"+50244445555"}'::jsonb
                WHERE id = ?
                """, fixture.orderId());

        LogisticsHistoryDetailResponse result = service.detail(
                fixture.branchId(), PickingSourceType.order, fixture.orderId());

        assertThat(result.summary().deliveryMethod())
                .isEqualTo(LogisticsHistoryDeliveryMethod.store_pickup);
        assertThat(result.summary().contactPhone()).isEqualTo("+50244445555");
        assertThat(result.summary().deliveredAt()).isNotNull();
        assertThat(result.summary().storePickupDeliveryId()).isNull();
    }

    @Test
    void includesTransferAndCancelledTransferUsingSourceBranchScope() {
        TransferFixture active = transfer("preparing");
        TransferFixture transfer = transfer("cancelled");

        PageResponse<?> allTransfers = service.search(
                fixture.branchId(), null, null, "transfer", null, null, 0, 20);

        PageResponse<?> result = service.search(
                fixture.branchId(),
                transfer.number(),
                "cancelled",
                "transfer",
                null,
                null,
                0,
                20);
        LogisticsHistoryDetailResponse detail = service.detail(
                fixture.branchId(), PickingSourceType.transfer, transfer.id());

        assertThat(allTransfers.totalItems()).isEqualTo(2);
        assertThat(result.items()).hasSize(1);
        assertThat(active.id()).isNotEqualTo(transfer.id());
        assertThat(detail.summary().orderId()).isNull();
        assertThat(detail.summary().orderReference()).isEqualTo(transfer.number());
        assertThat(detail.summary().deliveryMethod())
                .isEqualTo(LogisticsHistoryDeliveryMethod.transfer);
        assertThat(detail.summary().operationalStatus()).isEqualTo("cancelled");
    }

    @Test
    void detailProjectsLotsAndSerialsFromThePersistedPickingSelection() {
        UUID lotId = UUID.randomUUID();
        jdbc.update(
                "UPDATE products SET tracking_lot = true, tracking_serial = true WHERE id = ?",
                fixture.productId());
        jdbc.update("""
                INSERT INTO inventory_lots
                    (id, tenant_id, product_id, lot_number, expiration_date)
                VALUES (?, ?, ?, 'LOT-HISTORY', DATE '2027-12-31')
                """, lotId, fixture.tenantId(), fixture.productId());
        jdbc.update("""
                UPDATE picking_items SET picked_traces = ?::jsonb WHERE picking_order_id = ?
                """,
                "[{\"locationId\":\"" + fixture.locationId()
                        + "\",\"lotId\":\"" + lotId
                        + "\",\"quantity\":5.000,\"serialNumbers\":[\"SER-1\",\"SER-2\"]}]",
                pickingId);

        LogisticsHistoryDetailResponse result = service.detail(
                fixture.branchId(), PickingSourceType.order, fixture.orderId());

        assertThat(result.lines()).singleElement().satisfies(line ->
                assertThat(line.trackingSelections()).singleElement().satisfies(trace -> {
                    assertThat(trace.lotNumber()).isEqualTo("LOT-HISTORY");
                    assertThat(trace.expirationDate()).isEqualTo(LocalDate.parse("2027-12-31"));
                    assertThat(trace.serialNumbers()).containsExactly("SER-1", "SER-2");
                }));
    }

    @Test
    void deniesAnUnauthorizedBranchAndDoesNotRevealAnotherSource() {
        given(branchAccessResolver.resolve(any()))
                .willReturn(new BranchAccess(false, Set.of()));

        assertThatThrownBy(() -> service.detail(
                        fixture.branchId(), PickingSourceType.order, fixture.orderId()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getStatus())
                                .isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN));
    }

    @Test
    void rejectsUnknownFiltersAndMissingOrForeignSources() {
        assertThatThrownBy(() -> service.search(
                        fixture.branchId(), null, "unknown", null, null, null, 0, 20))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.search(
                        fixture.branchId(), null, null, "courier", null, null, 0, 20))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.detail(
                        fixture.branchId(), PickingSourceType.order, UUID.randomUUID()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getStatus())
                                .isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND));
    }

    @Test
    void requiresTheInventoryCapabilityBeforeReadingHistory() {
        doThrow(BusinessException.forbidden(
                        "CAPABILITY_REQUIRED", "Capacidad de inventario requerida."))
                .when(capabilityGuard)
                .ensureTenantCapability(
                        fixture.tenantId(),
                        com.omniretail.backend.shared.security.SaasCapability.inventory);

        assertThatThrownBy(() -> service.search(
                        fixture.branchId(), null, null, null, null, null, 0, 20))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getStatus())
                                .isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN));
    }

    @Test
    void mixesDispatchedOrdersAndInTransitTransfersWithCorrectTotalsAndStablePagination() {
        jdbc.update("UPDATE orders SET status = 'dispatched' WHERE id = ?", fixture.orderId());
        transfer("inTransit");
        transfer("received");

        PageResponse<LogisticsHistoryRowResponse> firstPage = service.search(
                fixture.branchId(), null, "dispatched", "inTransit", null, null, null, 0, 1);
        PageResponse<LogisticsHistoryRowResponse> secondPage = service.search(
                fixture.branchId(), null, "dispatched", "inTransit", null, null, null, 1, 1);

        assertThat(firstPage.totalItems()).isEqualTo(2);
        assertThat(firstPage.totalPages()).isEqualTo(2);
        assertThat(firstPage.items()).hasSize(1);
        assertThat(secondPage.items()).hasSize(1);
        assertThat(firstPage.items().getFirst().operationalStatus())
                .isNotEqualTo(secondPage.items().getFirst().operationalStatus());
        Set<String> statuses = Set.of(
                firstPage.items().getFirst().operationalStatus(),
                secondPage.items().getFirst().operationalStatus());
        assertThat(statuses).containsExactlyInAnyOrder("dispatched", "inTransit");
    }

    @Test
    void mixesDeliveredOrdersAndReceivedTransfers() {
        jdbc.update("UPDATE orders SET status = 'delivered', delivered_at = now() WHERE id = ?", fixture.orderId());
        transfer("received");
        transfer("inTransit");

        PageResponse<LogisticsHistoryRowResponse> result = service.search(
                fixture.branchId(), null, "delivered", "received", null, null, null, 0, 10);

        assertThat(result.totalItems()).isEqualTo(2);
        assertThat(result.items()).extracting(LogisticsHistoryRowResponse::operationalStatus)
                .containsExactlyInAnyOrder("delivered", "received");
        assertThat(result.items()).extracting(LogisticsHistoryRowResponse::sourceType)
                .containsExactlyInAnyOrder(PickingSourceType.order, PickingSourceType.transfer);
    }

    @Test
    void cancelledStatusWithoutTransferStatusMatchesBothCancelledOrdersAndTransfers() {
        jdbc.update("UPDATE orders SET status = 'cancelled' WHERE id = ?", fixture.orderId());
        transfer("cancelled");
        transfer("inTransit");

        PageResponse<LogisticsHistoryRowResponse> result = service.search(
                fixture.branchId(), null, "cancelled", null, null, null, null, 0, 10);

        assertThat(result.totalItems()).isEqualTo(2);
        assertThat(result.items()).allMatch(row -> "cancelled".equals(row.operationalStatus()));
        assertThat(result.items()).extracting(LogisticsHistoryRowResponse::sourceType)
                .containsExactlyInAnyOrder(PickingSourceType.order, PickingSourceType.transfer);
    }

    @Test
    void packingStatusWithoutTransferStatusMatchesOnlyOrders() {
        jdbc.update("UPDATE orders SET status = 'packing' WHERE id = ?", fixture.orderId());
        transfer("inTransit");

        PageResponse<LogisticsHistoryRowResponse> result = service.search(
                fixture.branchId(), null, "packing", null, null, null, null, 0, 10);

        assertThat(result.totalItems()).isEqualTo(1);
        assertThat(result.items()).singleElement().satisfies(row -> {
            assertThat(row.sourceType()).isEqualTo(PickingSourceType.order);
            assertThat(row.operationalStatus()).isEqualTo("packing");
        });
    }

    @Test
    void transferStatusOnlyMatchesOnlyTransfersInThatStatus() {
        jdbc.update("UPDATE orders SET status = 'dispatched' WHERE id = ?", fixture.orderId());
        transfer("inTransit");
        transfer("received");

        PageResponse<LogisticsHistoryRowResponse> result = service.search(
                fixture.branchId(), null, null, "inTransit", null, null, null, 0, 10);

        assertThat(result.totalItems()).isEqualTo(1);
        assertThat(result.items()).singleElement().satisfies(row -> {
            assertThat(row.sourceType()).isEqualTo(PickingSourceType.transfer);
            assertThat(row.operationalStatus()).isEqualTo("inTransit");
        });
    }

    @Test
    void rejectsInvalidTransferStatusWithDedicatedError() {
        assertThatThrownBy(() -> service.search(
                        fixture.branchId(), null, null, "foo", null, null, null, 0, 10))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getCode()).isEqualTo("LOGISTICS_HISTORY_TRANSFER_STATUS_INVALID");
                    assertThat(exception.getMessage()).isEqualTo("El estado de traslado no es v├ílido.");
                });
    }

    @Test
    void withoutStatusParametersReturnsBothOrdersAndTransfersWithoutFiltering() {
        transfer("inTransit");

        PageResponse<LogisticsHistoryRowResponse> result = service.search(
                fixture.branchId(), null, null, null, null, null, null, 0, 10);

        assertThat(result.totalItems()).isGreaterThanOrEqualTo(2);
        assertThat(result.items()).extracting(LogisticsHistoryRowResponse::sourceType)
                .contains(PickingSourceType.order, PickingSourceType.transfer);
    }

    private void insertPickingItem(
            UUID picking,
            UUID sourceLine,
            UUID product,
            UUID location,
            String quantity,
            String traces) {
        jdbc.update("""
                INSERT INTO picking_items
                    (id, tenant_id, picking_order_id, source_line_id, order_item_id,
                     product_id, requested_quantity, picked_quantity, location_id,
                     picked_traces, status)
                VALUES (?, ?, ?, ?, ?, ?, ?::numeric, ?::numeric, ?, ?::jsonb, 'completed')
                """,
                UUID.randomUUID(), fixture.tenantId(), picking, sourceLine, sourceLine,
                product, quantity, quantity, location, traces);
    }

    private UUID dispatch(String status) {
        UUID dispatchId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO dispatches
                    (id, tenant_id, branch_id, source_type, source_id, order_id, packing_id,
                     status, transport_mode, carrier_name, tracking_number,
                     dispatched_by_user_id, dispatched_at, delivered_at)
                VALUES (?, ?, ?, 'order', ?, ?, ?, ?, 'third_party', 'Carrier', 'TRACK-1',
                        ?, now(), CASE WHEN ? = 'delivered' THEN now() ELSE null END)
                """,
                dispatchId, fixture.tenantId(), fixture.branchId(), fixture.orderId(),
                fixture.orderId(), fixture.packingId(), status, fixture.userId(), status);
        return dispatchId;
    }

    private UUID simpleOrderWithPicking(String reference, Instant createdAt) {
        UUID orderId = UUID.randomUUID();
        UUID picking = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO orders
                    (id, tenant_id, branch_id, order_number, source, guest_customer, status,
                     delivery_method, transport_mode, delivery_address, notification_contact,
                     subtotal, discount_total, shipping_total, total, tracking_token)
                VALUES (?, ?, ?, ?, 'ecommerce', '{"name":"Otro cliente"}'::jsonb,
                        'confirmed', 'home_delivery', 'third_party',
                        '{"recipientName":"Otro cliente","recipientPhone":"+50233334444"}'::jsonb,
                        '{"emailMode":"send","email":"other@example.com"}'::jsonb,
                        0, 0, 0, 0, ?)
                """,
                orderId,
                fixture.tenantId(),
                fixture.branchId(),
                reference,
                UUID.randomUUID().toString());
        jdbc.update("""
                INSERT INTO picking_orders
                    (id, tenant_id, branch_id, source_type, source_id, order_id,
                     status, priority, created_at, updated_at)
                VALUES (?, ?, ?, 'order', ?, ?, 'pending', 'normal', ?, ?)
                """,
                picking,
                fixture.tenantId(),
                fixture.branchId(),
                orderId,
                orderId,
                java.sql.Timestamp.from(createdAt),
                java.sql.Timestamp.from(createdAt));
        return orderId;
    }

    private TransferFixture transfer(String status) {
        UUID destination = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        UUID transferItemId = UUID.randomUUID();
        UUID transferPicking = UUID.randomUUID();
        String number = "TR-HISTORY-" + transferId.toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, 'Destino', 'warehouse', 'active')
                """, destination, fixture.tenantId(), "DST-" + destination.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO inventory_transfers
                    (id, tenant_id, number, source_branch_id, destination_branch_id, status,
                     operation_id, operation_fingerprint, prepared_by_user_id, prepared_at,
                     cancelled_by_user_id, cancelled_at, cancel_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'fingerprint', ?, now(),
                        CASE WHEN ? = 'cancelled' THEN ? ELSE null END,
                        CASE WHEN ? = 'cancelled' THEN now() ELSE null END,
                        CASE WHEN ? = 'cancelled' THEN 'Cancelada' ELSE null END)
                """,
                transferId, fixture.tenantId(), number, fixture.branchId(), destination, status,
                "operation-" + transferId, fixture.userId(), status, fixture.userId(), status, status);
        jdbc.update("""
                INSERT INTO inventory_transfer_items
                    (id, tenant_id, transfer_id, product_id, requested_quantity,
                     dispatched_quantity, received_quantity)
                VALUES (?, ?, ?, ?, 3.000, 0.000, 0.000)
                """, transferItemId, fixture.tenantId(), transferId, fixture.productId());
        jdbc.update("""
                INSERT INTO picking_orders
                    (id, tenant_id, branch_id, source_type, source_id, status, priority)
                VALUES (?, ?, ?, 'transfer', ?, ?, 'normal')
                """, transferPicking, fixture.tenantId(), fixture.branchId(), transferId,
                "cancelled".equals(status) ? "cancelled" : "pending");
        jdbc.update("""
                INSERT INTO picking_items
                    (id, tenant_id, picking_order_id, source_line_id, product_id,
                     requested_quantity, picked_quantity, location_id, status)
                VALUES (?, ?, ?, ?, ?, 3.000, 0.000, ?, 'pending')
                """, UUID.randomUUID(), fixture.tenantId(), transferPicking, transferItemId,
                fixture.productId(), fixture.locationId());
        return new TransferFixture(transferId, number);
    }

    private record TransferFixture(UUID id, String number) {}
}

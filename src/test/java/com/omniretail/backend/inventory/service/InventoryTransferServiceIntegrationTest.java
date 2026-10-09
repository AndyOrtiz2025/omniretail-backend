package com.omniretail.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.ecommerce.entity.InventoryReservationSourceType;
import com.omniretail.backend.ecommerce.entity.InventoryReservationStatus;
import com.omniretail.backend.ecommerce.repository.InventoryReservationRepository;
import com.omniretail.backend.inventory.dto.ApproveInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.CancelInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.CreateInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.InventoryTransferRequestEffectiveStatus;
import com.omniretail.backend.inventory.dto.InventoryTransferResponse;
import com.omniretail.backend.inventory.dto.InventoryTransferTrackingSelectionRequest;
import com.omniretail.backend.inventory.dto.ReceiveInventoryTransferItemRequest;
import com.omniretail.backend.inventory.dto.ReceiveInventoryTransferRequest;
import com.omniretail.backend.inventory.entity.InventoryTransferReason;
import com.omniretail.backend.inventory.entity.InventoryTransferRequestStatus;
import com.omniretail.backend.inventory.entity.InventoryTransferStatus;
import com.omniretail.backend.inventory.repository.InventoryTransferItemRepository;
import com.omniretail.backend.inventory.repository.InventoryTransferRepository;
import com.omniretail.backend.inventory.repository.InventoryTransferReceiptItemRepository;
import com.omniretail.backend.inventory.repository.InventoryTransferReceiptRepository;
import com.omniretail.backend.inventory.repository.InventoryTransferRequestRepository;
import com.omniretail.backend.logistics.entity.PickingSourceType;
import com.omniretail.backend.logistics.entity.PackingSourceType;
import com.omniretail.backend.logistics.dto.ConfirmTransferDispatchRequest;
import com.omniretail.backend.logistics.dto.PackingChecklistRequest;
import com.omniretail.backend.logistics.dto.PackingVersionedRequest;
import com.omniretail.backend.logistics.dto.PickingTrackingSelectionRequest;
import com.omniretail.backend.logistics.dto.RegisterPackingLabelPrintRequest;
import com.omniretail.backend.logistics.dto.SavePackingPreparationRequest;
import com.omniretail.backend.logistics.dto.UpdatePickingItemRequest;
import com.omniretail.backend.logistics.repository.PackingRepository;
import com.omniretail.backend.logistics.repository.PickingItemRepository;
import com.omniretail.backend.logistics.repository.PickingOrderRepository;
import com.omniretail.backend.logistics.service.DispatchService;
import com.omniretail.backend.logistics.service.PackingService;
import com.omniretail.backend.logistics.service.PickingService;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantEntitlementResolver;
import com.omniretail.backend.shared.security.TenantEntitlements;
import java.math.BigDecimal;
import java.time.Year;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InventoryTransferServiceIntegrationTest {

    @Autowired private InventoryTransferService service;
    @Autowired private InventoryTransferRequestRepository requests;
    @Autowired private InventoryTransferRepository transfers;
    @Autowired private InventoryTransferItemRepository items;
    @Autowired private InventoryTransferReceiptRepository receipts;
    @Autowired private InventoryTransferReceiptItemRepository receiptItems;
    @Autowired private InventoryReservationRepository reservations;
    @Autowired private PickingOrderRepository pickingOrders;
    @Autowired private PickingItemRepository pickingItems;
    @Autowired private PackingRepository packings;
    @Autowired private PickingService pickingService;
    @Autowired private PackingService packingService;
    @Autowired private DispatchService dispatchService;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;
    @MockitoBean private BranchAccessResolver branchAccessResolver;
    @MockitoBean private TenantEntitlementResolver entitlements;

    private Fixture fixture;

    @BeforeEach
    void setUp() {
        fixture = fixture("10.000");
        useActor(fixture);
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(true, Set.of()));
        given(entitlements.resolve(any())).willReturn(
                new TenantEntitlements(true, true, EnumSet.allOf(SaasCapability.class)));
    }

    @Test
    void approvalCreatesTransferItemReservationAndAnnualNumberAtomically() {
        UUID requestId = createRequest();

        InventoryTransferResponse transfer = service.approve(
                requestId, new ApproveInventoryTransferRequest("approve-1", "aprobada"));

        int year = Year.now(ZoneId.of("America/Guatemala")).getValue();
        assertThat(transfer.number()).isEqualTo("TR-" + year + "-00001");
        assertThat(transfer.status()).isEqualTo(InventoryTransferStatus.preparing);
        assertThat(transfer.items()).singleElement().satisfies(item -> {
            assertThat(item.sourceRequestId()).isEqualTo(requestId);
            assertThat(item.requestedQuantity()).isEqualByComparingTo("2.000");
        });
        assertThat(requests.findByTenantIdAndId(fixture.tenantId(), requestId)
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(InventoryTransferRequestStatus.approved);
        assertThat(reservations.findByTenantIdAndSourceTypeAndSourceId(
                        fixture.tenantId(),
                        InventoryReservationSourceType.transfer,
                        transfer.id()))
                .singleElement()
                .satisfies(reservation -> {
                    assertThat(reservation.getSourceId()).isEqualTo(transfer.id());
                    assertThat(reservation.getSourceLineId())
                            .isEqualTo(transfer.items().getFirst().id());
                    assertThat(reservation.getOrderId()).isNull();
                    assertThat(reservation.getOrderItemId()).isNull();
                    assertThat(reservation.getStatus()).isEqualTo(InventoryReservationStatus.active);
                });
        var picking = pickingOrders
                .findByTenantIdAndSourceTypeAndSourceId(
                        fixture.tenantId(), PickingSourceType.transfer, transfer.id())
                .orElseThrow();
        assertThat(pickingService.ensureForTransfer(fixture.tenantId(), transfer.id()).getId())
                .isEqualTo(picking.getId());
        assertThat(picking.getBranchId()).isEqualTo(fixture.sourceBranchId());
        assertThat(picking.getOrderId()).isNull();
        assertThat(pickingItems.findByTenantIdAndPickingOrderId(
                        fixture.tenantId(), picking.getId()))
                .singleElement()
                .satisfies(pickingItem -> {
                    assertThat(pickingItem.getSourceLineId())
                            .isEqualTo(transfer.items().getFirst().id());
                    assertThat(pickingItem.getOrderItemId()).isNull();
                    assertThat(pickingItem.getRequestedQuantity())
                            .isEqualByComparingTo("2.000");
                });
        assertBalance("10.000", "2.000");
    }

    @Test
    void transferTraversesPickingPackingAndDispatchExactlyOnce() {
        UUID requestId = createRequest();
        InventoryTransferResponse transfer = service.approve(
                requestId, new ApproveInventoryTransferRequest("approve-pipeline", null));
        var picking = pickingOrders
                .findByTenantIdAndSourceTypeAndSourceId(
                        fixture.tenantId(), PickingSourceType.transfer, transfer.id())
                .orElseThrow();
        var pickingItem = pickingItems
                .findByTenantIdAndPickingOrderId(fixture.tenantId(), picking.getId())
                .getFirst();

        assertThat(pickingService.getQueue(fixture.sourceBranchId()))
                .anySatisfy(row -> {
                    assertThat(row.sourceType()).isEqualTo(PickingSourceType.transfer);
                    assertThat(row.sourceId()).isEqualTo(transfer.id());
                    assertThat(row.sourceReference()).isEqualTo(transfer.number());
                    assertThat(row.orderId()).isNull();
                });
        assertThat(pickingService.getDetail(fixture.sourceBranchId(), picking.getId()))
                .satisfies(detail -> {
                    assertThat(detail.sourceReference()).isEqualTo(transfer.number());
                    assertThat(detail.customerName()).isNull();
                    assertThat(detail.deliveryMethod()).isNull();
                    assertThat(detail.lines()).singleElement().satisfies(line -> {
                        assertThat(line.sourceLineId()).isEqualTo(transfer.items().getFirst().id());
                        assertThat(line.orderItemId()).isNull();
                    });
                });
        assertThatThrownBy(() -> pickingService.getDetail(
                        fixture.destinationBranchId(), picking.getId()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("PICKING_NOT_FOUND"));
        pickingService.assign(fixture.sourceBranchId(), picking.getId());
        pickingService.updateItem(
                fixture.sourceBranchId(),
                picking.getId(),
                pickingItem.getId(),
                new UpdatePickingItemRequest(new BigDecimal("2.000"), null, "pick-transfer"));
        pickingService.complete(fixture.sourceBranchId(), picking.getId());
        assertBalance("10.000", "2.000");
        assertThat(reservations.findByTenantIdAndSourceTypeAndSourceId(
                        fixture.tenantId(),
                        InventoryReservationSourceType.transfer,
                        transfer.id()))
                .singleElement()
                .extracting(reservation -> reservation.getStatus())
                .isEqualTo(InventoryReservationStatus.active);

        var packing = packings
                .findByTenantIdAndBranchIdAndSourceTypeAndSourceId(
                        fixture.tenantId(),
                        fixture.sourceBranchId(),
                        PackingSourceType.transfer,
                        transfer.id())
                .orElseThrow();
        assertThatThrownBy(() -> dispatchService.confirmTransfer(
                        fixture.sourceBranchId(),
                        transfer.id(),
                        new ConfirmTransferDispatchRequest("premature-transfer")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("PACKING_NOT_FINALIZED"));
        var prepared = packingService.savePreparation(
                fixture.sourceBranchId(),
                packing.getId(),
                new SavePackingPreparationRequest(
                        packing.getVersion(),
                        "prepare-transfer",
                        new PackingChecklistRequest(true, true, true),
                        new BigDecimal("1.500"),
                        1));
        var labelled = packingService.generateLabel(
                fixture.sourceBranchId(),
                packing.getId(),
                new PackingVersionedRequest(
                        prepared.packing().version(), "label-transfer"));
        var printed = packingService.registerLabelPrint(
                fixture.sourceBranchId(),
                packing.getId(),
                new RegisterPackingLabelPrintRequest(
                        labelled.packing().version(),
                        "print-transfer",
                        labelled.packing().labelGenerationId()));
        var finalized = packingService.finalizePacking(
                fixture.sourceBranchId(),
                packing.getId(),
                new PackingVersionedRequest(
                        printed.packing().version(), "finalize-transfer"));
        var finalizeReplay = packingService.finalizePacking(
                fixture.sourceBranchId(),
                packing.getId(),
                new PackingVersionedRequest(
                        printed.packing().version(), "finalize-transfer"));
        assertThat(finalized.orderStatus()).isNull();
        assertThat(finalized.transferStatus()).isEqualTo(InventoryTransferStatus.preparing);
        assertThat(finalizeReplay.idempotent()).isTrue();
        assertThat(finalizeReplay.packing().sourceReference()).isEqualTo(transfer.number());
        assertBalance("10.000", "2.000");

        assertThat(dispatchService.getQueue(fixture.sourceBranchId()))
                .anySatisfy(row -> {
                    assertThat(row.sourceId()).isEqualTo(transfer.id());
                    assertThat(row.sourceReference()).isEqualTo(transfer.number());
                    assertThat(row.orderId()).isNull();
                });
        var dispatched = dispatchService.confirmTransfer(
                fixture.sourceBranchId(),
                transfer.id(),
                new ConfirmTransferDispatchRequest("dispatch-transfer"));
        var replay = dispatchService.confirmTransfer(
                fixture.sourceBranchId(),
                transfer.id(),
                new ConfirmTransferDispatchRequest("dispatch-transfer"));

        assertThat(dispatched.transferStatus()).isEqualTo(InventoryTransferStatus.inTransit);
        assertThat(replay.idempotent()).isTrue();
        assertThat(replay.dispatchId()).isEqualTo(dispatched.dispatchId());
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                UUID.randomUUID(),
                fixture.tenantId(),
                UserType.employee,
                UUID.randomUUID(),
                fixture.sourceBranchId(),
                UUID.randomUUID()));
        assertThatThrownBy(() -> dispatchService.confirmTransfer(
                        fixture.sourceBranchId(),
                        transfer.id(),
                        new ConfirmTransferDispatchRequest("dispatch-transfer")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("DISPATCH_OPERATION_ID_REUSED"));
        assertThat(transfers.findByTenantIdAndId(fixture.tenantId(), transfer.id())
                        .orElseThrow())
                .satisfies(savedTransfer -> {
                    assertThat(savedTransfer.getStatus()).isEqualTo(InventoryTransferStatus.inTransit);
                    assertThat(savedTransfer.getDispatchedByUserId()).isEqualTo(fixture.actorId());
                    assertThat(savedTransfer.getDispatchedAt()).isNotNull();
                });
        assertThat(items.findByTenantIdAndTransferIdOrderByIdAsc(
                        fixture.tenantId(), transfer.id()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.getDispatchedQuantity()).isEqualByComparingTo("2.000");
                    assertThat(item.getReceivedQuantity()).isEqualByComparingTo("0.000");
                });
        assertThat(service.listRequests(null, null, null, PageRequest.of(0, 20)).items())
                .filteredOn(response -> response.id().equals(requestId))
                .singleElement()
                .satisfies(response -> assertThat(response.effectiveStatus())
                        .isEqualTo(InventoryTransferRequestEffectiveStatus.inTransit));
        assertBalance("8.000", "0.000");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM inventory_movements WHERE reference_type = 'transfer' AND reference_id = ?",
                        Long.class,
                        transfer.id()))
                .isOne();
    }

    @Test
    void concurrentTransferDispatchReplaysWithoutDuplicatingInventoryEffects()
            throws Exception {
        ReadyTransfer readyTransfer = readyTransfer("concurrent-dispatch");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<com.omniretail.backend.logistics.dto.DispatchResponse>> futures =
                    java.util.stream.IntStream.range(0, 2)
                            .mapToObj(index -> executor.submit(() -> {
                                ready.countDown();
                                if (!start.await(10, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException(
                                            "Los despachos no iniciaron a tiempo.");
                                }
                                return dispatchService.confirmTransfer(
                                        fixture.sourceBranchId(),
                                        readyTransfer.transfer().id(),
                                        new ConfirmTransferDispatchRequest(
                                                "same-transfer-dispatch"));
                            }))
                            .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            var first = futures.get(0).get(20, TimeUnit.SECONDS);
            var second = futures.get(1).get(20, TimeUnit.SECONDS);
            assertThat(first.dispatchId()).isEqualTo(second.dispatchId());
            assertThat(List.of(first.idempotent(), second.idempotent()))
                    .containsExactlyInAnyOrder(false, true);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM dispatches WHERE source_type = 'transfer' AND source_id = ?",
                            Long.class,
                            readyTransfer.transfer().id()))
                    .isOne();
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM inventory_movements WHERE reference_type = 'transfer' AND reference_id = ?",
                            Long.class,
                            readyTransfer.transfer().id()))
                    .isOne();
            assertBalance("8.000", "0.000");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancellationAndDispatchCannotBothComplete() throws Exception {
        ReadyTransfer readyTransfer = readyTransfer("cancel-vs-dispatch");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> dispatch = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) return false;
                try {
                    dispatchService.confirmTransfer(
                            fixture.sourceBranchId(),
                            readyTransfer.transfer().id(),
                            new ConfirmTransferDispatchRequest("race-dispatch"));
                    return true;
                } catch (BusinessException exception) {
                    return false;
                }
            });
            Future<Boolean> cancellation = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) return false;
                try {
                    service.cancelTransfer(
                            readyTransfer.transfer().id(),
                            new CancelInventoryTransferRequest("race-cancel"));
                    return true;
                } catch (BusinessException exception) {
                    return false;
                }
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                            dispatch.get(20, TimeUnit.SECONDS),
                            cancellation.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            InventoryTransferStatus status = transfers
                    .findByTenantIdAndId(
                            fixture.tenantId(), readyTransfer.transfer().id())
                    .orElseThrow()
                    .getStatus();
            assertThat(status)
                    .isIn(InventoryTransferStatus.inTransit, InventoryTransferStatus.cancelled);
            if (status == InventoryTransferStatus.inTransit) {
                assertBalance("8.000", "0.000");
                assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM inventory_movements WHERE reference_type = 'transfer' AND reference_id = ?",
                                Long.class,
                                readyTransfer.transfer().id()))
                        .isOne();
            } else {
                assertBalance("10.000", "0.000");
                assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM inventory_movements WHERE reference_type = 'transfer' AND reference_id = ?",
                                Long.class,
                                readyTransfer.transfer().id()))
                        .isZero();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void traceableCancellationAndDispatchUseCompatibleInventoryLockOrder() throws Exception {
        TraceableTransfer traceable = readyTraceableTransfer("trace-cancel-vs-dispatch");
        InventoryTransferResponse transfer = traceable.transfer();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> dispatch = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) return false;
                try {
                    dispatchService.confirmTransfer(
                            fixture.sourceBranchId(),
                            transfer.id(),
                            new ConfirmTransferDispatchRequest("trace-race-dispatch"));
                    return true;
                } catch (BusinessException exception) {
                    return false;
                }
            });
            Future<Boolean> cancellation = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) return false;
                try {
                    service.cancelTransfer(
                            transfer.id(),
                            new CancelInventoryTransferRequest("trace-race-cancel"));
                    return true;
                } catch (BusinessException exception) {
                    return false;
                }
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                            dispatch.get(20, TimeUnit.SECONDS),
                            cancellation.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            InventoryTransferStatus status = transfers
                    .findByTenantIdAndId(fixture.tenantId(), transfer.id())
                    .orElseThrow()
                    .getStatus();
            if (status == InventoryTransferStatus.inTransit) {
                assertBalance("8.000", "0.000");
                assertLotBalance(
                        fixture.sourceBranchId(), traceable.sourceLocationId(), traceable.lotId(),
                        "0.000", "0.000");
                assertSerial(
                        "TRACE-A", "IN_TRANSIT", fixture.sourceBranchId(), traceable.sourceLocationId());
                assertSerial(
                        "TRACE-B", "IN_TRANSIT", fixture.sourceBranchId(), traceable.sourceLocationId());
            } else {
                assertThat(status).isEqualTo(InventoryTransferStatus.cancelled);
                assertBalance("10.000", "0.000");
                assertLotBalance(
                        fixture.sourceBranchId(), traceable.sourceLocationId(), traceable.lotId(),
                        "2.000", "0.000");
                assertSerial(
                        "TRACE-A", "AVAILABLE", fixture.sourceBranchId(), traceable.sourceLocationId());
                assertSerial(
                        "TRACE-B", "AVAILABLE", fixture.sourceBranchId(), traceable.sourceLocationId());
            }
            assertThat(jdbc.queryForObject(
                            "SELECT picked_traces IS NOT NULL FROM picking_items "
                                    + "WHERE source_line_id = ?",
                            Boolean.class,
                            transfer.items().getFirst().id()))
                    .isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void traceableTransferDispatchAndPartialReceivePreserveExactLotAndSerials() {
        TraceableTransfer traceable = readyTraceableTransfer("trace-partial");
        InventoryTransferResponse transfer = traceable.transfer();

        var packing = packings
                .findByTenantIdAndBranchIdAndSourceTypeAndSourceId(
                        fixture.tenantId(),
                        fixture.sourceBranchId(),
                        PackingSourceType.transfer,
                        transfer.id())
                .orElseThrow();
        assertThat(packingService.getDetail(fixture.sourceBranchId(), packing.getId())
                        .preparedContents())
                .singleElement()
                .satisfies(content -> {
                    assertThat(content.trackingSelections()).singleElement().satisfies(selection -> {
                        assertThat(selection.locationId()).isEqualTo(traceable.sourceLocationId());
                        assertThat(selection.lotId()).isEqualTo(traceable.lotId());
                        assertThat(selection.lotNumber()).isEqualTo("TRACE-LOT-trace-partial");
                        assertThat(selection.quantity()).isEqualByComparingTo("2.000");
                        assertThat(selection.serialNumbers()).containsExactly("TRACE-A", "TRACE-B");
                    });
                });

        var dispatch = dispatchService.confirmTransfer(
                fixture.sourceBranchId(),
                transfer.id(),
                new ConfirmTransferDispatchRequest("dispatch-trace-partial"));
        var dispatchReplay = dispatchService.confirmTransfer(
                fixture.sourceBranchId(),
                transfer.id(),
                new ConfirmTransferDispatchRequest("dispatch-trace-partial"));

        assertThat(dispatch.transferStatus()).isEqualTo(InventoryTransferStatus.inTransit);
        assertThat(dispatchReplay.idempotent()).isTrue();
        assertThat(dispatchReplay.dispatchId()).isEqualTo(dispatch.dispatchId());
        assertBalance("8.000", "0.000");
        assertLotBalance(
                fixture.sourceBranchId(), traceable.sourceLocationId(), traceable.lotId(),
                "0.000", "0.000");
        assertSerial("TRACE-A", "IN_TRANSIT", fixture.sourceBranchId(), traceable.sourceLocationId());
        assertSerial("TRACE-B", "IN_TRANSIT", fixture.sourceBranchId(), traceable.sourceLocationId());
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM inventory_movement_traces trace
                        JOIN inventory_movements movement ON movement.id = trace.movement_id
                        WHERE movement.reference_type = 'transfer'
                          AND movement.reference_id = ?
                          AND movement.reference_line_id = ?
                          AND trace.serial_id IS NOT NULL
                          AND trace.lot_id IS NULL
                        """,
                        Long.class,
                        transfer.id(),
                        transfer.items().getFirst().id()))
                .isEqualTo(2L);

        ReceiveInventoryTransferRequest firstRequest = traceReceiptRequest(
                transfer, "trace-receipt-a", traceable.lotId(), "TRACE-A");
        var first = service.receiveTransfer(transfer.id(), firstRequest);
        var replay = service.receiveTransfer(transfer.id(), firstRequest);

        assertThat(first.transferStatus()).isEqualTo(InventoryTransferStatus.inTransit);
        assertThat(replay.idempotent()).isTrue();
        assertThat(replay.id()).isEqualTo(first.id());
        assertDestinationBalance("1.000", "0.000");
        assertLotBalance(
                fixture.destinationBranchId(), fixture.destinationLocationId(), traceable.lotId(),
                "1.000", "0.000");
        assertSerial(
                "TRACE-A", "AVAILABLE", fixture.destinationBranchId(), fixture.destinationLocationId());
        assertSerial("TRACE-B", "IN_TRANSIT", fixture.sourceBranchId(), traceable.sourceLocationId());

        assertCode(
                () -> service.receiveTransfer(
                        transfer.id(),
                        traceReceiptRequest(
                                transfer, "trace-receipt-a", traceable.lotId(), "TRACE-B")),
                "INVENTORY_TRANSFER_RECEIPT_CONFIRMATION_CONFLICT");
        assertCode(
                () -> service.receiveTransfer(
                        transfer.id(),
                        traceReceiptRequest(
                                transfer, "trace-receipt-duplicate", traceable.lotId(), "TRACE-A")),
                "TRANSFER_SERIAL_NOT_RECEIVABLE");

        jdbc.update(
                """
                INSERT INTO inventory_serials
                    (id, tenant_id, branch_id, location_id, product_id, serial_number,
                     lot_id, status, version)
                VALUES (?, ?, ?, ?, ?, 'TRACE-INJECTED', ?, 'IN_TRANSIT', 0)
                """,
                UUID.randomUUID(),
                fixture.tenantId(),
                fixture.sourceBranchId(),
                traceable.sourceLocationId(),
                fixture.productId(),
                traceable.lotId());
        assertCode(
                () -> service.receiveTransfer(
                        transfer.id(),
                        traceReceiptRequest(
                                transfer,
                                "trace-receipt-injected",
                                traceable.lotId(),
                                "TRACE-INJECTED")),
                "TRANSFER_SERIAL_NOT_RECEIVABLE");

        var second = service.receiveTransfer(
                transfer.id(),
                traceReceiptRequest(
                        transfer, "trace-receipt-b", traceable.lotId(), "TRACE-B"));

        assertThat(second.transferStatus()).isEqualTo(InventoryTransferStatus.received);
        assertDestinationBalance("2.000", "0.000");
        assertLotBalance(
                fixture.destinationBranchId(), fixture.destinationLocationId(), traceable.lotId(),
                "2.000", "0.000");
        assertSerial(
                "TRACE-B", "AVAILABLE", fixture.destinationBranchId(), fixture.destinationLocationId());
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM inventory_movement_traces trace
                        JOIN inventory_movements movement ON movement.id = trace.movement_id
                        JOIN inventory_transfer_receipts receipt
                          ON receipt.id = movement.reference_id
                        WHERE receipt.transfer_id = ?
                          AND movement.reference_type = 'receipt'
                          AND movement.reference_line_id = ?
                          AND trace.serial_id IS NOT NULL
                          AND trace.lot_id IS NULL
                        """,
                        Long.class,
                        transfer.id(),
                        transfer.items().getFirst().id()))
                .isEqualTo(2L);
    }

    @Test
    void cancellingPreparingTraceableTransferReleasesPhysicalAndAggregateReservations() {
        TraceableTransfer traceable = readyTraceableTransfer("trace-cancel");

        assertBalance("10.000", "2.000");
        assertLotBalance(
                fixture.sourceBranchId(), traceable.sourceLocationId(), traceable.lotId(),
                "2.000", "2.000");
        assertSerial("TRACE-A", "RESERVED", fixture.sourceBranchId(), traceable.sourceLocationId());
        assertSerial("TRACE-B", "RESERVED", fixture.sourceBranchId(), traceable.sourceLocationId());

        InventoryTransferResponse cancelled = service.cancelTransfer(
                traceable.transfer().id(), new CancelInventoryTransferRequest("cancel traceable"));

        assertThat(cancelled.status()).isEqualTo(InventoryTransferStatus.cancelled);
        assertBalance("10.000", "0.000");
        assertLotBalance(
                fixture.sourceBranchId(), traceable.sourceLocationId(), traceable.lotId(),
                "2.000", "0.000");
        assertSerial("TRACE-A", "AVAILABLE", fixture.sourceBranchId(), traceable.sourceLocationId());
        assertSerial("TRACE-B", "AVAILABLE", fixture.sourceBranchId(), traceable.sourceLocationId());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM inventory_movements WHERE reference_id = ?",
                        Long.class,
                        traceable.transfer().id()))
                .isZero();
    }

    @Test
    void totalReceiptIncreasesDestinationStockAndCompletesTransfer() {
        InventoryTransferResponse transfer = dispatchedTransfer("receipt-total", "2.000");

        var receipt = service.receiveTransfer(
                transfer.id(), receiptRequest(transfer, "receipt-total", "2.000"));

        assertThat(receipt.idempotent()).isFalse();
        assertThat(receipt.destinationBranchId()).isEqualTo(fixture.destinationBranchId());
        assertThat(receipt.destinationLocationId()).isEqualTo(fixture.destinationLocationId());
        assertThat(receipt.transferStatus()).isEqualTo(InventoryTransferStatus.received);
        assertThat(receipt.items()).singleElement().satisfies(item -> {
            assertThat(item.transferItemId()).isEqualTo(transfer.items().getFirst().id());
            assertThat(item.receivedQuantity()).isEqualByComparingTo("2.000");
        });
        assertThat(service.getTransfer(transfer.id())).satisfies(saved -> {
            assertThat(saved.status()).isEqualTo(InventoryTransferStatus.received);
            assertThat(saved.items().getFirst().receivedQuantity()).isEqualByComparingTo("2.000");
            assertThat(saved.receivedByUserId()).isEqualTo(fixture.actorId());
            assertThat(saved.receivedAt()).isNotNull();
        });
        assertBalance("8.000", "0.000");
        assertDestinationBalance("2.000", "0.000");
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM inventory_movements
                        WHERE tenant_id = ? AND branch_id = ? AND product_id = ?
                          AND type = 'in' AND reference_type = 'transfer'
                          AND reference_id = ? AND to_location_id = ?
                        """,
                        Long.class,
                        fixture.tenantId(),
                        fixture.destinationBranchId(),
                        fixture.productId(),
                        transfer.id(),
                        fixture.destinationLocationId()))
                .isOne();
        assertThat(service.listRequests(null, null, null, PageRequest.of(0, 20)).items())
                .filteredOn(item -> item.id().equals(transfer.items().getFirst().sourceRequestId()))
                .singleElement()
                .satisfies(item -> assertThat(item.effectiveStatus())
                        .isEqualTo(InventoryTransferRequestEffectiveStatus.received));
    }

    @Test
    void partialReceiptsAccumulateAndOnlyTheLastOneCompletesTransfer() {
        InventoryTransferResponse transfer = dispatchedTransfer("receipt-partial", "10.000");

        var first = service.receiveTransfer(
                transfer.id(), receiptRequest(transfer, "receipt-partial-a", "4.000"));
        assertThat(first.transferStatus()).isEqualTo(InventoryTransferStatus.inTransit);
        assertThat(service.getTransfer(transfer.id()).items().getFirst().receivedQuantity())
                .isEqualByComparingTo("4.000");

        var second = service.receiveTransfer(
                transfer.id(), receiptRequest(transfer, "receipt-partial-b", "6.000"));
        assertThat(second.transferStatus()).isEqualTo(InventoryTransferStatus.received);
        assertThat(service.getTransfer(transfer.id()).items().getFirst().receivedQuantity())
                .isEqualByComparingTo("10.000");
        assertThat(receipts.findByTenantIdAndTransferIdOrderByReceivedAtAsc(
                        fixture.tenantId(), transfer.id()))
                .hasSize(2);
        assertDestinationBalance("10.000", "0.000");
    }

    @Test
    void overReceiptRollsBackWithoutPartialEffects() {
        InventoryTransferResponse transfer = dispatchedTransfer("receipt-over", "10.000");
        service.receiveTransfer(
                transfer.id(), receiptRequest(transfer, "receipt-over-a", "4.000"));

        assertCode(
                () -> service.receiveTransfer(
                        transfer.id(), receiptRequest(transfer, "receipt-over-b", "7.000")),
                "INVENTORY_TRANSFER_OVER_RECEIPT");

        assertThat(service.getTransfer(transfer.id()).items().getFirst().receivedQuantity())
                .isEqualByComparingTo("4.000");
        assertDestinationBalance("4.000", "0.000");
        assertThat(receipts.findByTenantIdAndTransferIdOrderByReceivedAtAsc(
                        fixture.tenantId(), transfer.id()))
                .hasSize(1);
        assertThat(inboundMovementCount(transfer.id())).isOne();
    }

    @Test
    void exactTerminalReplayIsIdempotentButCollisionsAndNewReceiptsFail() {
        InventoryTransferResponse transfer = dispatchedTransfer("receipt-replay", "2.000");
        ReceiveInventoryTransferRequest request =
                receiptRequest(transfer, "receipt-replay", "2.000");
        var first = service.receiveTransfer(transfer.id(), request);
        var replay = service.receiveTransfer(transfer.id(), request);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.idempotent()).isTrue();
        assertThat(receipts.findByTenantIdAndTransferIdOrderByReceivedAtAsc(
                        fixture.tenantId(), transfer.id()))
                .hasSize(1);
        assertThat(receiptItems.findByTenantIdAndReceiptIdOrderByIdAsc(
                        fixture.tenantId(), first.id()))
                .hasSize(1);
        assertDestinationBalance("2.000", "0.000");
        assertThat(inboundMovementCount(transfer.id())).isOne();

        assertCode(
                () -> service.receiveTransfer(
                        transfer.id(), receiptRequest(transfer, "receipt-replay", "1.000")),
                "INVENTORY_TRANSFER_RECEIPT_CONFIRMATION_CONFLICT");
        assertCode(
                () -> service.receiveTransfer(
                        transfer.id(), receiptRequest(transfer, "receipt-new", "1.000")),
                "INVALID_INVENTORY_TRANSFER_STATE");
    }

    @Test
    void destinationLocationAndDestinationBranchAccessAreEnforced() {
        InventoryTransferResponse transfer = dispatchedTransfer("receipt-location", "2.000");
        UUID sourceLocation = insertLocation(
                fixture.tenantId(), fixture.sourceBranchId(), "SOURCE-RECEIPT", "active");
        UUID inactiveLocation = insertLocation(
                fixture.tenantId(), fixture.destinationBranchId(), "INACTIVE", "inactive");
        Fixture otherTenant = fixture("1.000");

        assertCode(
                () -> service.receiveTransfer(
                        transfer.id(),
                        receiptRequest(transfer, "wrong-branch", "1.000", sourceLocation)),
                "INVENTORY_TRANSFER_DESTINATION_LOCATION_INVALID");
        assertCode(
                () -> service.receiveTransfer(
                        transfer.id(),
                        receiptRequest(transfer, "inactive", "1.000", inactiveLocation)),
                "INVENTORY_TRANSFER_DESTINATION_LOCATION_INVALID");
        assertCode(
                () -> service.receiveTransfer(
                        transfer.id(),
                        receiptRequest(
                                transfer,
                                "other-tenant",
                                "1.000",
                                otherTenant.destinationLocationId())),
                "INVENTORY_TRANSFER_DESTINATION_LOCATION_INVALID");

        given(branchAccessResolver.resolve(any())).willReturn(
                new BranchAccess(false, Set.of(fixture.destinationBranchId())));
        service.receiveTransfer(
                transfer.id(), receiptRequest(transfer, "destination-only", "1.000"));
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(false, Set.of()));
        assertCode(
                () -> service.receiveTransfer(
                        transfer.id(), receiptRequest(transfer, "no-access", "1.000")),
                "BRANCH_ACCESS_DENIED");
    }

    @Test
    void receiptRejectsUnknownDuplicateInvalidAndTraceableItems() {
        InventoryTransferResponse transfer = dispatchedTransfer("receipt-validation", "2.000");
        UUID itemId = transfer.items().getFirst().id();

        assertCode(
                () -> service.receiveTransfer(
                        transfer.id(),
                        new ReceiveInventoryTransferRequest(
                                "duplicate",
                                fixture.destinationLocationId(),
                                List.of(
                                        new ReceiveInventoryTransferItemRequest(
                                                itemId, BigDecimal.ONE),
                                        new ReceiveInventoryTransferItemRequest(
                                                itemId, BigDecimal.ONE)))),
                "INVALID_INVENTORY_TRANSFER_RECEIPT");
        assertCode(
                () -> service.receiveTransfer(
                        transfer.id(),
                        new ReceiveInventoryTransferRequest(
                                "unknown",
                                fixture.destinationLocationId(),
                                List.of(new ReceiveInventoryTransferItemRequest(
                                        UUID.randomUUID(), BigDecimal.ONE)))),
                "INVENTORY_TRANSFER_RECEIPT_ITEM_NOT_FOUND");
        assertCode(
                () -> service.receiveTransfer(
                        transfer.id(),
                        new ReceiveInventoryTransferRequest(
                                "zero",
                                fixture.destinationLocationId(),
                                List.of(new ReceiveInventoryTransferItemRequest(
                                        itemId, BigDecimal.ZERO)))),
                "INVALID_INVENTORY_TRANSFER_RECEIPT");

        jdbc.update("UPDATE products SET tracking_lot = true WHERE id = ?", fixture.productId());
        assertCode(
                () -> service.receiveTransfer(
                        transfer.id(), receiptRequest(transfer, "traceability", "1.000")),
                "TRACKING_SELECTIONS_REQUIRED");
        jdbc.update(
                """
                UPDATE products
                SET product_type = 'kit', tracking_stock = false, tracking_lot = false,
                    tracking_expiration = false, tracking_serial = false
                WHERE id = ?
                """,
                fixture.productId());
        assertCode(
                () -> service.receiveTransfer(
                        transfer.id(), receiptRequest(transfer, "kit", "1.000")),
                "INVENTORY_TRANSFER_PRODUCT_UNSUPPORTED");
        assertDestinationBalance("0.000", "0.000");
    }

    @Test
    void preparingCancelledAndCrossTenantTransfersCannotBeReceived() {
        UUID requestId = createRequest();
        InventoryTransferResponse preparing = service.approve(
                requestId, new ApproveInventoryTransferRequest("approve-not-dispatched", null));
        assertCode(
                () -> service.receiveTransfer(
                        preparing.id(), receiptRequest(preparing, "preparing", "1.000")),
                "INVALID_INVENTORY_TRANSFER_STATE");
        InventoryTransferResponse cancelled = service.cancelTransfer(
                preparing.id(), new CancelInventoryTransferRequest("cancelled"));
        assertCode(
                () -> service.receiveTransfer(
                        cancelled.id(), receiptRequest(cancelled, "cancelled", "1.000")),
                "INVALID_INVENTORY_TRANSFER_STATE");

        Fixture original = fixture;
        Fixture otherTenant = fixture("1.000");
        useActor(otherTenant);
        assertCode(
                () -> service.receiveTransfer(
                        preparing.id(),
                        new ReceiveInventoryTransferRequest(
                                "cross-tenant",
                                original.destinationLocationId(),
                                List.of(new ReceiveInventoryTransferItemRequest(
                                        preparing.items().getFirst().id(), BigDecimal.ONE)))),
                "INVENTORY_TRANSFER_NOT_FOUND");
    }

    @Test
    void concurrentReceiptsCannotOverReceive() throws Exception {
        InventoryTransferResponse transfer = dispatchedTransfer("receipt-concurrent", "10.000");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) return false;
                        try {
                            service.receiveTransfer(
                                    transfer.id(),
                                    receiptRequest(
                                            transfer,
                                            "receipt-concurrent-" + index,
                                            "6.000"));
                            return true;
                        } catch (BusinessException exception) {
                            return false;
                        }
                    }))
                    .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(
                            futures.get(0).get(20, TimeUnit.SECONDS),
                            futures.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(service.getTransfer(transfer.id()).items().getFirst().receivedQuantity())
                    .isEqualByComparingTo("6.000");
            assertDestinationBalance("6.000", "0.000");
            assertThat(inboundMovementCount(transfer.id())).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentExactConfirmationReplaysWithoutDuplicateEffects() throws Exception {
        InventoryTransferResponse transfer = dispatchedTransfer("receipt-same", "2.000");
        ReceiveInventoryTransferRequest request = receiptRequest(
                transfer, "receipt-same-confirmation", "2.000");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var futures = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Las recepciones no iniciaron a tiempo.");
                        }
                        return service.receiveTransfer(transfer.id(), request);
                    }))
                    .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var first = futures.get(0).get(20, TimeUnit.SECONDS);
            var second = futures.get(1).get(20, TimeUnit.SECONDS);
            assertThat(first.id()).isEqualTo(second.id());
            assertThat(List.of(first.idempotent(), second.idempotent()))
                    .containsExactlyInAnyOrder(false, true);
            assertDestinationBalance("2.000", "0.000");
            assertThat(inboundMovementCount(transfer.id())).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void insufficientStockRollsBackTransferItemRequestAndCounter() {
        UUID requestId = createRequest();
        jdbc.update(
                "UPDATE inventory_balances SET quantity = 1.000 WHERE tenant_id = ?",
                fixture.tenantId());

        assertThatThrownBy(() -> service.approve(
                        requestId, new ApproveInventoryTransferRequest("approve-fails", null)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("INSUFFICIENT_STOCK"));

        assertThat(requests.findByTenantIdAndId(fixture.tenantId(), requestId)
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(InventoryTransferRequestStatus.requested);
        assertThat(transfers.findByTenantIdAndOperationId(fixture.tenantId(), "approve-fails"))
                .isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM inventory_transfer_items WHERE tenant_id = ?",
                        Long.class,
                        fixture.tenantId()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM document_counters WHERE tenant_id = ?",
                        Long.class,
                        fixture.tenantId()))
                .isZero();
    }

    @Test
    void sameApprovalOperationReplaysWithoutDuplicatingTransferOrReservation() {
        UUID requestId = createRequest();
        ApproveInventoryTransferRequest approval =
                new ApproveInventoryTransferRequest("approve-replay", "aprobada");

        InventoryTransferResponse first = service.approve(requestId, approval);
        InventoryTransferResponse replay = service.approve(requestId, approval);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(transfers.findByTenantIdAndOperationId(fixture.tenantId(), "approve-replay"))
                .isPresent();
        assertThat(items.findByTenantIdAndTransferIdOrderByIdAsc(fixture.tenantId(), first.id()))
                .hasSize(1);
        assertThat(reservations.findByTenantIdAndSourceTypeAndSourceId(
                        fixture.tenantId(), InventoryReservationSourceType.transfer, first.id()))
                .hasSize(1);
        assertBalance("10.000", "2.000");
    }

    @Test
    void pessimisticRequestLockSerializesConcurrentApproval() throws Exception {
        UUID requestId = createRequest();
        ApproveInventoryTransferRequest approval =
                new ApproveInventoryTransferRequest("approve-concurrent", null);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<InventoryTransferResponse>> futures = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Las aprobaciones no iniciaron a tiempo.");
                        }
                        return service.approve(requestId, approval);
                    }))
                    .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            InventoryTransferResponse first = futures.get(0).get(20, TimeUnit.SECONDS);
            InventoryTransferResponse second = futures.get(1).get(20, TimeUnit.SECONDS);
            assertThat(second.id()).isEqualTo(first.id());
            assertThat(items.findByTenantIdAndTransferIdOrderByIdAsc(
                            fixture.tenantId(), first.id()))
                    .hasSize(1);
            assertBalance("10.000", "2.000");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancellingPreparingTransferReleasesReservationWithoutChangingPhysicalStock() {
        UUID requestId = createRequest();
        InventoryTransferResponse approved = service.approve(
                requestId, new ApproveInventoryTransferRequest("approve-cancel", null));

        InventoryTransferResponse cancelled = service.cancelTransfer(
                approved.id(), new CancelInventoryTransferRequest("solicitud anulada"));

        assertThat(cancelled.status()).isEqualTo(InventoryTransferStatus.cancelled);
        assertThat(reservations.findByTenantIdAndSourceTypeAndSourceId(
                        fixture.tenantId(), InventoryReservationSourceType.transfer, approved.id()))
                .singleElement()
                .extracting(reservation -> reservation.getStatus())
                .isEqualTo(InventoryReservationStatus.released);
        assertBalance("10.000", "0.000");
    }

    // ------------------------------- ubicacion operativa unica en la sucursal destino

    @Test
    void transferToADestinationWithAnAssignedActiveLocationIsApprovedDispatchedAndReceived() {
        enableLocations();
        assignDestination(fixture.destinationLocationId());

        InventoryTransferResponse transfer = dispatchedTransfer("loc-ok", "2.000");
        var receipt = service.receiveTransfer(transfer.id(), receiptRequest(transfer, "loc-ok", "2.000"));

        assertThat(receipt.transferStatus()).isEqualTo(InventoryTransferStatus.received);
        assertDestinationBalance("2.000", "0.000");
        assertBalance("8.000", "0.000");
    }

    @Test
    void approvalIsRejectedBeforeAnyEffectWhenTheDestinationProductHasNoAssignedLocation() {
        enableLocations();
        UUID requestId = createRequest();

        assertCode(
                () -> service.approve(requestId, new ApproveInventoryTransferRequest("approve-noassign", null)),
                InventoryOperationalLocationService.TRANSFER_DESTINATION_INVALID_CODE);

        assertThat(requests.findByTenantIdAndId(fixture.tenantId(), requestId).orElseThrow().getStatus())
                .isEqualTo(InventoryTransferRequestStatus.requested);
        assertThat(transfers.findByTenantIdAndOperationId(fixture.tenantId(), "approve-noassign")).isEmpty();
        assertBalance("10.000", "0.000");
    }

    @Test
    void approvalIsRejectedWhenTheAssignedDestinationLocationIsInactive() {
        enableLocations();
        UUID inactive = insertLocation(
                fixture.tenantId(), fixture.destinationBranchId(), "INACTIVE", "inactive");
        assignDestination(inactive);
        UUID requestId = createRequest();

        assertCode(
                () -> service.approve(requestId, new ApproveInventoryTransferRequest("approve-inactive", null)),
                InventoryOperationalLocationService.TRANSFER_DESTINATION_INVALID_CODE);

        assertBalance("10.000", "0.000");
    }

    @Test
    void dispatchIsRejectedWhenTheDestinationConfigurationChangedAfterApprovalAndRetrySucceedsOnceFixed() {
        InventoryTransferResponse transfer = readyTransfer("cfg-change", "2.000").transfer();
        // Despues de aprobar y preparar, el negocio habilita ubicaciones y el destino no tiene asignacion.
        enableLocations();

        assertCode(
                () -> dispatchService.confirmTransfer(
                        fixture.sourceBranchId(),
                        transfer.id(),
                        new ConfirmTransferDispatchRequest("dispatch-cfg-change")),
                InventoryOperationalLocationService.TRANSFER_DESTINATION_INVALID_CODE);

        // Nada cambio: sigue en preparacion, con su reserva activa y sin mover existencias.
        assertThat(service.getTransfer(transfer.id()).status()).isEqualTo(InventoryTransferStatus.preparing);
        assertThat(reservations.findByTenantIdAndSourceTypeAndSourceId(
                        fixture.tenantId(), InventoryReservationSourceType.transfer, transfer.id()))
                .singleElement()
                .satisfies(reservation ->
                        assertThat(reservation.getStatus()).isEqualTo(InventoryReservationStatus.active));
        assertBalance("10.000", "2.000");

        // Correccion y reintento con la misma operacion.
        assignDestination(fixture.destinationLocationId());
        dispatchService.confirmTransfer(
                fixture.sourceBranchId(),
                transfer.id(),
                new ConfirmTransferDispatchRequest("dispatch-cfg-change"));
        assertThat(service.getTransfer(transfer.id()).status()).isEqualTo(InventoryTransferStatus.inTransit);
        assertBalance("8.000", "0.000");
    }

    @Test
    void receiptAtTheWrongLocationFailsWithoutEffectsAndPartialReceiptsThenComplete() {
        enableLocations();
        assignDestination(fixture.destinationLocationId());
        UUID other = insertLocation(fixture.tenantId(), fixture.destinationBranchId(), "OTHER", "active");
        InventoryTransferResponse transfer = dispatchedTransfer("loc-retry", "4.000");

        assertCode(
                () -> service.receiveTransfer(
                        transfer.id(), receiptRequest(transfer, "loc-retry-a", "4.000", other)),
                InventoryOperationalLocationService.LOCATION_MISMATCH_CODE);

        assertThat(service.getTransfer(transfer.id()).status()).isEqualTo(InventoryTransferStatus.inTransit);
        assertThat(service.getTransfer(transfer.id()).items().getFirst().receivedQuantity())
                .isEqualByComparingTo("0.000");
        assertThat(receipts.findByTenantIdAndTransferIdOrderByReceivedAtAsc(
                        fixture.tenantId(), transfer.id()))
                .isEmpty();
        assertThat(inboundMovementCount(transfer.id())).isZero();
        assertDestinationBalance("0.000", "0.000");

        // Reintento con la misma confirmacion y la ubicacion asignada, en dos recepciones parciales.
        var first = service.receiveTransfer(
                transfer.id(), receiptRequest(transfer, "loc-retry-a", "1.000"));
        assertThat(first.transferStatus()).isEqualTo(InventoryTransferStatus.inTransit);
        var second = service.receiveTransfer(
                transfer.id(), receiptRequest(transfer, "loc-retry-b", "3.000"));
        assertThat(second.transferStatus()).isEqualTo(InventoryTransferStatus.received);
        assertDestinationBalance("4.000", "0.000");
    }

    @Test
    void merchandiseAlreadyInTransitIsRecoveredByAssigningTheDestinationLocationAndRetrying() {
        InventoryTransferResponse transfer = dispatchedTransfer("in-transit", "2.000");
        // Despacho anterior a la politica; luego el negocio habilita ubicaciones.
        enableLocations();

        assertCode(
                () -> service.receiveTransfer(
                        transfer.id(), receiptRequest(transfer, "in-transit", "2.000")),
                InventoryOperationalLocationService.LOCATION_NOT_ASSIGNED_CODE);
        assertThat(service.getTransfer(transfer.id()).status()).isEqualTo(InventoryTransferStatus.inTransit);
        assertDestinationBalance("0.000", "0.000");

        assignDestination(fixture.destinationLocationId());
        var receipt = service.receiveTransfer(
                transfer.id(), receiptRequest(transfer, "in-transit", "2.000"));

        assertThat(receipt.transferStatus()).isEqualTo(InventoryTransferStatus.received);
        assertDestinationBalance("2.000", "0.000");
    }

    @Test
    void transfersAreUnaffectedWhenLocationsAreDisabled() {
        InventoryTransferResponse transfer = dispatchedTransfer("loc-off", "2.000");

        var receipt = service.receiveTransfer(transfer.id(), receiptRequest(transfer, "loc-off", "2.000"));

        assertThat(receipt.transferStatus()).isEqualTo(InventoryTransferStatus.received);
        assertDestinationBalance("2.000", "0.000");
    }

    private void enableLocations() {
        jdbc.update(
                """
                INSERT INTO business_capabilities_configs (id, tenant_id, preset, supports_multiple_locations)
                VALUES (?, ?, 'custom', true)
                """,
                UUID.randomUUID(),
                fixture.tenantId());
    }

    private void assignDestination(UUID locationId) {
        jdbc.update(
                """
                INSERT INTO product_inventory_settings (tenant_id, branch_id, product_id, default_location_id)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (tenant_id, branch_id, product_id)
                DO UPDATE SET default_location_id = EXCLUDED.default_location_id
                """,
                fixture.tenantId(),
                fixture.destinationBranchId(),
                fixture.productId(),
                locationId);
    }

    private ReadyTransfer readyTransfer(String suffix) {
        return readyTransfer(suffix, "2.000");
    }

    private TraceableTransfer readyTraceableTransfer(String suffix) {
        UUID sourceLocation = insertLocation(
                fixture.tenantId(), fixture.sourceBranchId(), "TRACE-SOURCE", "active");
        UUID lotId = UUID.randomUUID();
        jdbc.update(
                """
                UPDATE products
                SET tracking_lot = true, tracking_serial = true
                WHERE id = ?
                """,
                fixture.productId());
        jdbc.update(
                """
                INSERT INTO inventory_lots (id, tenant_id, product_id, lot_number)
                VALUES (?, ?, ?, ?)
                """,
                lotId,
                fixture.tenantId(),
                fixture.productId(),
                "TRACE-LOT-" + suffix);
        jdbc.update(
                """
                INSERT INTO inventory_lot_balances
                    (id, tenant_id, branch_id, location_id, lot_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, ?, ?, 2.000, 0.000)
                """,
                UUID.randomUUID(),
                fixture.tenantId(),
                fixture.sourceBranchId(),
                sourceLocation,
                lotId);
        jdbc.update(
                """
                INSERT INTO inventory_serials
                    (id, tenant_id, branch_id, location_id, product_id, serial_number,
                     lot_id, status, version)
                VALUES (?, ?, ?, ?, ?, 'TRACE-A', ?, 'AVAILABLE', 0),
                       (?, ?, ?, ?, ?, 'TRACE-B', ?, 'AVAILABLE', 0)
                """,
                UUID.randomUUID(), fixture.tenantId(), fixture.sourceBranchId(), sourceLocation,
                fixture.productId(), lotId,
                UUID.randomUUID(), fixture.tenantId(), fixture.sourceBranchId(), sourceLocation,
                fixture.productId(), lotId);

        UUID requestId = createRequest("2.000");
        InventoryTransferResponse transfer = service.approve(
                requestId,
                new ApproveInventoryTransferRequest("approve-" + suffix, null));
        var picking = pickingOrders
                .findByTenantIdAndSourceTypeAndSourceId(
                        fixture.tenantId(), PickingSourceType.transfer, transfer.id())
                .orElseThrow();
        var pickingItem = pickingItems
                .findByTenantIdAndPickingOrderId(fixture.tenantId(), picking.getId())
                .getFirst();
        pickingService.assign(fixture.sourceBranchId(), picking.getId());
        pickingService.updateItem(
                fixture.sourceBranchId(),
                picking.getId(),
                pickingItem.getId(),
                new UpdatePickingItemRequest(
                        new BigDecimal("2.000"),
                        sourceLocation,
                        "pick-" + suffix,
                        List.of(new PickingTrackingSelectionRequest(
                                sourceLocation,
                                lotId,
                                new BigDecimal("2.000"),
                                List.of("TRACE-B", "TRACE-A")))));
        pickingService.complete(fixture.sourceBranchId(), picking.getId());
        finalizeTransferPacking(transfer, suffix);
        return new TraceableTransfer(transfer, sourceLocation, lotId);
    }

    private void finalizeTransferPacking(InventoryTransferResponse transfer, String suffix) {
        var packing = packings
                .findByTenantIdAndBranchIdAndSourceTypeAndSourceId(
                        fixture.tenantId(),
                        fixture.sourceBranchId(),
                        PackingSourceType.transfer,
                        transfer.id())
                .orElseThrow();
        var prepared = packingService.savePreparation(
                fixture.sourceBranchId(),
                packing.getId(),
                new SavePackingPreparationRequest(
                        packing.getVersion(),
                        "prepare-" + suffix,
                        new PackingChecklistRequest(true, true, true),
                        new BigDecimal("1.500"),
                        1));
        var labelled = packingService.generateLabel(
                fixture.sourceBranchId(),
                packing.getId(),
                new PackingVersionedRequest(
                        prepared.packing().version(), "label-" + suffix));
        var printed = packingService.registerLabelPrint(
                fixture.sourceBranchId(),
                packing.getId(),
                new RegisterPackingLabelPrintRequest(
                        labelled.packing().version(),
                        "print-" + suffix,
                        labelled.packing().labelGenerationId()));
        packingService.finalizePacking(
                fixture.sourceBranchId(),
                packing.getId(),
                new PackingVersionedRequest(
                        printed.packing().version(), "finalize-" + suffix));
    }

    private ReceiveInventoryTransferRequest traceReceiptRequest(
            InventoryTransferResponse transfer,
            String confirmationId,
            UUID lotId,
            String serialNumber) {
        return new ReceiveInventoryTransferRequest(
                confirmationId,
                fixture.destinationLocationId(),
                List.of(new ReceiveInventoryTransferItemRequest(
                        transfer.items().getFirst().id(),
                        BigDecimal.ONE,
                        List.of(new InventoryTransferTrackingSelectionRequest(
                                lotId, BigDecimal.ONE, List.of(serialNumber))))));
    }

    private void assertLotBalance(
            UUID branchId,
            UUID locationId,
            UUID lotId,
            String quantity,
            String reservedQuantity) {
        assertThat(jdbc.queryForObject(
                        """
                        SELECT quantity FROM inventory_lot_balances
                        WHERE tenant_id = ? AND branch_id = ? AND location_id = ? AND lot_id = ?
                        """,
                        BigDecimal.class,
                        fixture.tenantId(), branchId, locationId, lotId))
                .isEqualByComparingTo(quantity);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT reserved_quantity FROM inventory_lot_balances
                        WHERE tenant_id = ? AND branch_id = ? AND location_id = ? AND lot_id = ?
                        """,
                        BigDecimal.class,
                        fixture.tenantId(), branchId, locationId, lotId))
                .isEqualByComparingTo(reservedQuantity);
    }

    private void assertSerial(
            String serialNumber, String status, UUID branchId, UUID locationId) {
        assertThat(jdbc.queryForMap(
                        """
                        SELECT status, branch_id, location_id FROM inventory_serials
                        WHERE tenant_id = ? AND product_id = ? AND serial_number = ?
                        """,
                        fixture.tenantId(), fixture.productId(), serialNumber))
                .containsEntry("status", status)
                .containsEntry("branch_id", branchId)
                .containsEntry("location_id", locationId);
    }

    private ReadyTransfer readyTransfer(String suffix, String quantity) {
        UUID requestId = createRequest(quantity);
        InventoryTransferResponse transfer = service.approve(
                requestId,
                new ApproveInventoryTransferRequest("approve-" + suffix, null));
        var picking = pickingOrders
                .findByTenantIdAndSourceTypeAndSourceId(
                        fixture.tenantId(), PickingSourceType.transfer, transfer.id())
                .orElseThrow();
        var pickingItem = pickingItems
                .findByTenantIdAndPickingOrderId(fixture.tenantId(), picking.getId())
                .getFirst();
        pickingService.assign(fixture.sourceBranchId(), picking.getId());
        pickingService.updateItem(
                fixture.sourceBranchId(),
                picking.getId(),
                pickingItem.getId(),
                new UpdatePickingItemRequest(
                        pickingItem.getRequestedQuantity(), null, "pick-" + suffix));
        pickingService.complete(fixture.sourceBranchId(), picking.getId());

        var packing = packings
                .findByTenantIdAndBranchIdAndSourceTypeAndSourceId(
                        fixture.tenantId(),
                        fixture.sourceBranchId(),
                        PackingSourceType.transfer,
                        transfer.id())
                .orElseThrow();
        var prepared = packingService.savePreparation(
                fixture.sourceBranchId(),
                packing.getId(),
                new SavePackingPreparationRequest(
                        packing.getVersion(),
                        "prepare-" + suffix,
                        new PackingChecklistRequest(true, true, true),
                        new BigDecimal("1.500"),
                        1));
        var labelled = packingService.generateLabel(
                fixture.sourceBranchId(),
                packing.getId(),
                new PackingVersionedRequest(
                        prepared.packing().version(), "label-" + suffix));
        var printed = packingService.registerLabelPrint(
                fixture.sourceBranchId(),
                packing.getId(),
                new RegisterPackingLabelPrintRequest(
                        labelled.packing().version(),
                        "print-" + suffix,
                        labelled.packing().labelGenerationId()));
        packingService.finalizePacking(
                fixture.sourceBranchId(),
                packing.getId(),
                new PackingVersionedRequest(
                        printed.packing().version(), "finalize-" + suffix));
        return new ReadyTransfer(transfer);
    }

    private InventoryTransferResponse dispatchedTransfer(String suffix, String quantity) {
        InventoryTransferResponse transfer = readyTransfer(suffix, quantity).transfer();
        dispatchService.confirmTransfer(
                fixture.sourceBranchId(),
                transfer.id(),
                new ConfirmTransferDispatchRequest("dispatch-" + suffix));
        return service.getTransfer(transfer.id());
    }

    private ReceiveInventoryTransferRequest receiptRequest(
            InventoryTransferResponse transfer, String confirmationId, String quantity) {
        return receiptRequest(
                transfer, confirmationId, quantity, fixture.destinationLocationId());
    }

    private ReceiveInventoryTransferRequest receiptRequest(
            InventoryTransferResponse transfer,
            String confirmationId,
            String quantity,
            UUID locationId) {
        return new ReceiveInventoryTransferRequest(
                confirmationId,
                locationId,
                List.of(new ReceiveInventoryTransferItemRequest(
                        transfer.items().getFirst().id(), new BigDecimal(quantity))));
    }

    private UUID createRequest() {
        return createRequest("2.000");
    }

    private UUID createRequest(String quantity) {
        return service.createRequest(new CreateInventoryTransferRequest(
                        fixture.destinationBranchId(),
                        fixture.sourceBranchId(),
                        fixture.productId(),
                        new BigDecimal(quantity),
                        InventoryTransferReason.replenishment,
                        "reposición"))
                .id();
    }

    private void useActor(Fixture value) {
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                value.actorId(),
                value.tenantId(),
                UserType.employee,
                UUID.randomUUID(),
                value.sourceBranchId(),
                UUID.randomUUID()));
    }

    private Fixture fixture(String stock) {
        UUID tenantId = UUID.randomUUID();
        UUID sourceBranchId = UUID.randomUUID();
        UUID destinationBranchId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID destinationLocationId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO tenants (id, name, slug) VALUES (?, 'Transfer lifecycle', ?)",
                tenantId,
                "transfer-lifecycle-" + tenantId);
        insertBranch(tenantId, sourceBranchId, "SOURCE");
        insertBranch(tenantId, destinationBranchId, "DESTINATION");
        jdbc.update(
                """
                INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status)
                VALUES (?, ?, ?, 'RECEIVING', 'Recepción', 'warehouse', 'active')
                """,
                destinationLocationId,
                tenantId,
                destinationBranchId);
        jdbc.update(
                """
                INSERT INTO users (id, tenant_id, name, email, type, branch_id)
                VALUES (?, ?, 'Operador', ?, 'employee', ?)
                """,
                actorId,
                tenantId,
                actorId + "@test.local",
                sourceBranchId);
        jdbc.update(
                "INSERT INTO categories (id, tenant_id, name, slug) VALUES (?, ?, 'General', ?)",
                categoryId,
                tenantId,
                "general-" + categoryId);
        jdbc.update(
                """
                INSERT INTO units
                    (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Unidad', 'u', 'unit', true, 'active')
                """,
                unitId,
                tenantId,
                "U-" + unitId.toString().substring(0, 8));
        jdbc.update(
                """
                INSERT INTO products
                    (id, tenant_id, sku, name, product_type, category_id, base_unit_id,
                     status, tracking_stock, tracking_lot, tracking_expiration, tracking_serial)
                VALUES (?, ?, ?, 'Producto traslado', 'physical', ?, ?,
                        'published', true, false, false, false)
                """,
                productId,
                tenantId,
                "SKU-" + productId.toString().substring(0, 8),
                categoryId,
                unitId);
        jdbc.update(
                """
                INSERT INTO inventory_balances
                    (tenant_id, branch_id, product_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, ?::numeric, 0.000)
                """,
                tenantId,
                sourceBranchId,
                productId,
                stock);
        return new Fixture(
                tenantId,
                sourceBranchId,
                destinationBranchId,
                destinationLocationId,
                productId,
                actorId);
    }

    private UUID insertLocation(
            UUID tenantId, UUID branchId, String code, String status) {
        UUID locationId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status)
                VALUES (?, ?, ?, ?, ?, 'warehouse', ?)
                """,
                locationId,
                tenantId,
                branchId,
                code + "-" + locationId.toString().substring(0, 8),
                code,
                status);
        return locationId;
    }

    private void insertBranch(UUID tenantId, UUID branchId, String code) {
        jdbc.update(
                """
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, ?, 'warehouse', 'active')
                """,
                branchId,
                tenantId,
                code,
                code);
    }

    private void assertBalance(String quantity, String reservedQuantity) {
        assertThat(jdbc.queryForObject(
                        """
                        SELECT quantity FROM inventory_balances
                        WHERE tenant_id = ? AND branch_id = ? AND product_id = ?
                        """,
                        BigDecimal.class,
                        fixture.tenantId(),
                        fixture.sourceBranchId(),
                        fixture.productId()))
                .isEqualByComparingTo(quantity);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT reserved_quantity FROM inventory_balances
                        WHERE tenant_id = ? AND branch_id = ? AND product_id = ?
                        """,
                        BigDecimal.class,
                        fixture.tenantId(),
                        fixture.sourceBranchId(),
                        fixture.productId()))
                .isEqualByComparingTo(reservedQuantity);
    }

    private void assertDestinationBalance(String quantity, String reservedQuantity) {
        assertThat(jdbc.queryForObject(
                        """
                        SELECT COALESCE(sum(quantity), 0) FROM inventory_balances
                        WHERE tenant_id = ? AND branch_id = ? AND product_id = ?
                          AND location_id = ?
                        """,
                        BigDecimal.class,
                        fixture.tenantId(),
                        fixture.destinationBranchId(),
                        fixture.productId(),
                        fixture.destinationLocationId()))
                .isEqualByComparingTo(quantity);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT COALESCE(sum(reserved_quantity), 0) FROM inventory_balances
                        WHERE tenant_id = ? AND branch_id = ? AND product_id = ?
                          AND location_id = ?
                        """,
                        BigDecimal.class,
                        fixture.tenantId(),
                        fixture.destinationBranchId(),
                        fixture.productId(),
                        fixture.destinationLocationId()))
                .isEqualByComparingTo(reservedQuantity);
    }

    private Long inboundMovementCount(UUID transferId) {
        return jdbc.queryForObject(
                """
                SELECT count(*) FROM inventory_movements
                WHERE tenant_id = ? AND branch_id = ? AND type = 'in'
                  AND reference_type = 'transfer' AND reference_id = ?
                """,
                Long.class,
                fixture.tenantId(),
                fixture.destinationBranchId(),
                transferId);
    }

    private static void assertCode(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(code));
    }

    private record Fixture(
            UUID tenantId,
            UUID sourceBranchId,
            UUID destinationBranchId,
            UUID destinationLocationId,
            UUID productId,
            UUID actorId) {}

    private record ReadyTransfer(InventoryTransferResponse transfer) {}

    private record TraceableTransfer(
            InventoryTransferResponse transfer, UUID sourceLocationId, UUID lotId) {}
}

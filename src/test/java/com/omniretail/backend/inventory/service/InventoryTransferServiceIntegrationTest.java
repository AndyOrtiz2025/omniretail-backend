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
import com.omniretail.backend.inventory.dto.InventoryTransferResponse;
import com.omniretail.backend.inventory.entity.InventoryTransferReason;
import com.omniretail.backend.inventory.entity.InventoryTransferRequestStatus;
import com.omniretail.backend.inventory.entity.InventoryTransferStatus;
import com.omniretail.backend.inventory.repository.InventoryTransferItemRepository;
import com.omniretail.backend.inventory.repository.InventoryTransferRepository;
import com.omniretail.backend.inventory.repository.InventoryTransferRequestRepository;
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
    @Autowired private InventoryReservationRepository reservations;
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
        assertBalance("10.000", "2.000");
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

    private UUID createRequest() {
        return service.createRequest(new CreateInventoryTransferRequest(
                        fixture.destinationBranchId(),
                        fixture.sourceBranchId(),
                        fixture.productId(),
                        new BigDecimal("2.000"),
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
        jdbc.update(
                "INSERT INTO tenants (id, name, slug) VALUES (?, 'Transfer lifecycle', ?)",
                tenantId,
                "transfer-lifecycle-" + tenantId);
        insertBranch(tenantId, sourceBranchId, "SOURCE");
        insertBranch(tenantId, destinationBranchId, "DESTINATION");
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
                tenantId, sourceBranchId, destinationBranchId, productId, actorId);
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

    private record Fixture(
            UUID tenantId,
            UUID sourceBranchId,
            UUID destinationBranchId,
            UUID productId,
            UUID actorId) {}
}

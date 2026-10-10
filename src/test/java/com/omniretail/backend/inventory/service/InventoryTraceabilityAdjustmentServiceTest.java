package com.omniretail.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.inventory.dto.InventoryAdjustmentRequest;
import com.omniretail.backend.inventory.dto.InventoryAdjustmentType;
import com.omniretail.backend.inventory.dto.InventoryLotAvailabilityDto;
import com.omniretail.backend.inventory.dto.InventoryMovementResponse;
import com.omniretail.backend.inventory.dto.InventorySerialAvailabilityDto;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
class InventoryTraceabilityAdjustmentServiceTest {

    @Autowired
    private InventoryTraceabilityAdjustmentService adjustmentService;

    @Test
    void expectedQuantityGuardsInboundAndOutboundOnTheLockedTargetBalance() {
        Fixture fixture = createFixture(false, false, false);
        authenticate(fixture);
        insertBalance(fixture, fixture.locationId(), "10", "3");
        insertBalance(fixture, fixture.secondLocationId(), "20", "0");

        adjustmentService.adjust(requestWithExpected(
                fixture, fixture.locationId(), InventoryAdjustmentType.out, "2", "10"));
        assertThat(balance(fixture, fixture.locationId())).isEqualByComparingTo("8");

        assertCode("COUNT_SNAPSHOT_STALE", () -> adjustmentService.adjust(requestWithExpected(
                fixture, fixture.locationId(), InventoryAdjustmentType.out, "1", "10")));
        assertCode("COUNT_SNAPSHOT_STALE", () -> adjustmentService.adjust(requestWithExpected(
                fixture, fixture.locationId(), InventoryAdjustmentType.in, "1", "28")));
        assertThat(balance(fixture, fixture.locationId())).isEqualByComparingTo("8");
        assertThat(balance(fixture, fixture.secondLocationId())).isEqualByComparingTo("20");

        adjustmentService.adjust(requestWithExpected(
                fixture, fixture.locationId(), InventoryAdjustmentType.in, "1", "8"));
        assertThat(balance(fixture, fixture.locationId())).isEqualByComparingTo("9");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM inventory_movements WHERE tenant_id = ?",
                        Long.class,
                        fixture.tenantId()))
                .isEqualTo(2);
    }

    @Test
    void expectedZeroAllowsInboundAdjustmentToCreateTheTargetBalance() {
        Fixture fixture = createFixture(false, false, false);
        authenticate(fixture);

        InventoryMovementResponse movement = adjustmentService.adjust(requestWithExpected(
                fixture, fixture.locationId(), InventoryAdjustmentType.in, "2", "0"));

        assertThat(movement.quantityBefore()).isEqualByComparingTo("0");
        assertThat(movement.quantityAfter()).isEqualByComparingTo("2");
        assertThat(balance(fixture, fixture.locationId())).isEqualByComparingTo("2");
        assertThat(count("inventory_movements", fixture.tenantId(), fixture.productId())).isOne();
    }

    @Test
    void failingTraceableInboundRollsBackNewBalanceAndMovement() {
        Fixture fixture = createFixture(false, false, true);
        authenticate(fixture);
        insertSerial(fixture, null, fixture.locationId(), "EXISTING-WITHOUT-BALANCE", "AVAILABLE");
        InventoryAdjustmentRequest request = new InventoryAdjustmentRequest(
                fixture.branchId(), fixture.productId(), InventoryAdjustmentType.in, BigDecimal.ONE,
                "Ajuste con rollback", "count_correction", UUID.randomUUID(), fixture.locationId(),
                null, null, null, List.of("EXISTING-WITHOUT-BALANCE"), BigDecimal.ZERO);

        assertCode("DUPLICATE_SERIAL", () -> adjustmentService.adjust(request));

        assertThat(count("inventory_balances", fixture.tenantId(), fixture.productId())).isZero();
        assertThat(count("inventory_movements", fixture.tenantId(), fixture.productId())).isZero();
        assertThat(count("inventory_movement_traces", fixture.tenantId(), null)).isZero();
        assertThat(count("inventory_serials", fixture.tenantId(), fixture.productId())).isOne();
    }

    @Test
    void omittedExpectedQuantityKeepsTheLegacyAdjustmentContract() {
        Fixture fixture = createFixture(false, false, false);
        authenticate(fixture);
        insertBalance(fixture, fixture.locationId(), "5", "0");

        adjustmentService.adjust(request(
                fixture,
                fixture.locationId(),
                InventoryAdjustmentType.out,
                "1",
                null,
                null,
                null,
                List.of()));

        assertThat(balance(fixture, fixture.locationId())).isEqualByComparingTo("4");
    }

    @Autowired
    private InventoryTraceabilityQueryService queryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private CurrentUser currentUser;

    @MockitoBean
    private TenantCapabilityGuard tenantCapabilityGuard;

    @MockitoBean
    private BranchAccessResolver branchAccessResolver;

    @BeforeEach
    void allowAllBranches() {
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(true, Set.of()));
    }

    @Test
    void normalInAndOutUseOneMovementEachAndNoTraceRows() {
        Fixture fixture = createFixture(false, false, false);
        authenticate(fixture);

        InventoryMovementResponse incoming = adjustmentService.adjust(request(
                fixture, fixture.locationId(), InventoryAdjustmentType.in, "5.000",
                null, null, null, null));
        InventoryMovementResponse outgoing = adjustmentService.adjust(request(
                fixture, fixture.locationId(), InventoryAdjustmentType.out, "2.000",
                null, null, null, null));

        assertThat(incoming.quantityBefore()).isEqualByComparingTo("0.000");
        assertThat(incoming.quantityAfter()).isEqualByComparingTo("5.000");
        assertThat(incoming.toLocationId()).isEqualTo(fixture.locationId());
        assertThat(outgoing.quantityBefore()).isEqualByComparingTo("5.000");
        assertThat(outgoing.quantityAfter()).isEqualByComparingTo("3.000");
        assertThat(outgoing.fromLocationId()).isEqualTo(fixture.locationId());
        assertThat(balance(fixture, fixture.locationId())).isEqualByComparingTo("3.000");
        assertThat(count("inventory_movements", fixture.tenantId(), fixture.productId())).isEqualTo(2);
        assertThat(count("inventory_movement_traces", fixture.tenantId(), null)).isZero();
    }

    @Test
    void normalOutRejectsInsufficientAggregateStock() {
        Fixture fixture = createFixture(false, false, false);
        authenticate(fixture);
        insertBalance(fixture, fixture.locationId(), "2.000", "1.000");

        assertCode("INSUFFICIENT_STOCK", () -> adjustmentService.adjust(request(
                fixture, fixture.locationId(), InventoryAdjustmentType.out, "2.000",
                null, null, null, null)));

        assertThat(balance(fixture, fixture.locationId())).isEqualByComparingTo("2.000");
        assertThat(count("inventory_movements", fixture.tenantId(), fixture.productId())).isZero();
    }

    @Test
    void disabledTrackingPayloadAndLocationFromAnotherBranchAreRejected() {
        Fixture fixture = createFixture(false, false, false);
        authenticate(fixture);
        UUID foreignLocation = UUID.randomUUID();
        insertLocation(
                fixture.tenantId(), fixture.secondBranchId(), foreignLocation,
                "OTHER-" + UUID.randomUUID());

        assertCode("INVALID_TRACKING_PAYLOAD", () -> adjustmentService.adjust(request(
                fixture, fixture.locationId(), InventoryAdjustmentType.in, "1.000",
                null, "NOT-ALLOWED", null, null)));
        assertCode("LOCATION_BRANCH_MISMATCH", () -> adjustmentService.adjust(request(
                fixture, foreignLocation, InventoryAdjustmentType.in, "1.000",
                null, null, null, null)));

        assertThat(count("inventory_balances", fixture.tenantId(), fixture.productId())).isZero();
        assertThat(count("inventory_movements", fixture.tenantId(), fixture.productId())).isZero();
    }

    @Test
    void lotInCreatesAndReusesLotAndLotOutCreatesOneTracePerMovement() {
        Fixture fixture = createFixture(true, true, false);
        authenticate(fixture);
        LocalDate expiration = LocalDate.now().plusDays(30);

        adjustmentService.adjust(request(
                fixture, fixture.locationId(), InventoryAdjustmentType.in, "2.000",
                null, "LOT-01", expiration, null));
        adjustmentService.adjust(request(
                fixture, fixture.locationId(), InventoryAdjustmentType.in, "3.000",
                null, "LOT-01", expiration, null));
        UUID lotId = lotId(fixture, "LOT-01");
        adjustmentService.adjust(request(
                fixture, fixture.locationId(), InventoryAdjustmentType.out, "2.000",
                lotId, null, null, null));

        assertThat(count("inventory_lots", fixture.tenantId(), fixture.productId())).isOne();
        assertThat(lotBalance(fixture, lotId, fixture.locationId())).isEqualByComparingTo("3.000");
        assertThat(balance(fixture, fixture.locationId())).isEqualByComparingTo("3.000");
        assertThat(count("inventory_movements", fixture.tenantId(), fixture.productId())).isEqualTo(3);
        assertThat(count("inventory_movement_traces", fixture.tenantId(), null)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM inventory_movement_traces WHERE tenant_id = ? AND lot_id = ? AND serial_id IS NULL",
                        Long.class, fixture.tenantId(), lotId))
                .isEqualTo(3L);
    }

    @Test
    void expirationIsRequiredAndMismatchRollsBackAggregateAndMovement() {
        Fixture fixture = createFixture(true, true, false);
        authenticate(fixture);

        assertCode("LOT_EXPIRATION_REQUIRED", () -> adjustmentService.adjust(request(
                fixture, fixture.locationId(), InventoryAdjustmentType.in, "1.000",
                null, "LOT-EXP", null, null)));
        LocalDate expiration = LocalDate.now().plusDays(10);
        adjustmentService.adjust(request(
                fixture, fixture.locationId(), InventoryAdjustmentType.in, "1.000",
                null, "LOT-EXP", expiration, null));

        assertCode("LOT_EXPIRATION_MISMATCH", () -> adjustmentService.adjust(request(
                fixture, fixture.locationId(), InventoryAdjustmentType.in, "1.000",
                null, "LOT-EXP", expiration.plusDays(1), null)));

        assertThat(balance(fixture, fixture.locationId())).isEqualByComparingTo("1.000");
        assertThat(count("inventory_movements", fixture.tenantId(), fixture.productId())).isOne();
        assertThat(count("inventory_lots", fixture.tenantId(), fixture.productId())).isOne();
    }

    @Test
    void pastExpirationIsRejectedBeforeLotOrBalanceCreation() {
        Fixture fixture = createFixture(true, true, false);
        authenticate(fixture);

        assertCode("LOT_EXPIRATION_INVALID", () -> adjustmentService.adjust(request(
                fixture, fixture.locationId(), InventoryAdjustmentType.in, "1.000",
                null, "EXPIRED", LocalDate.now().minusDays(2), null)));

        assertThat(count("inventory_lots", fixture.tenantId(), fixture.productId())).isZero();
        assertThat(count("inventory_balances", fixture.tenantId(), fixture.productId())).isZero();
    }

    @Test
    void lotOutCannotUseMissingBalanceOrHistoricalAggregateStock() {
        Fixture fixture = createFixture(true, false, false);
        authenticate(fixture);
        insertBalance(fixture, fixture.locationId(), "5.000", "0.000");
        UUID lotId = insertLot(fixture, "HISTORICAL", null);

        assertCode("LOT_NOT_FOUND", () -> adjustmentService.adjust(request(
                fixture, fixture.locationId(), InventoryAdjustmentType.out, "1.000",
                UUID.randomUUID(), null, null, null)));
        assertCode("INSUFFICIENT_TRACEABLE_LOT_STOCK", () -> adjustmentService.adjust(request(
                fixture, fixture.locationId(), InventoryAdjustmentType.out, "1.000",
                lotId, null, null, null)));

        assertThat(balance(fixture, fixture.locationId())).isEqualByComparingTo("5.000");
        assertThat(count("inventory_movements", fixture.tenantId(), fixture.productId())).isZero();
    }

    @Test
    void lotOutRejectsInsufficientAvailableLotQuantityWithoutPartialWrite() {
        Fixture fixture = createFixture(true, false, false);
        authenticate(fixture);
        insertBalance(fixture, fixture.locationId(), "5.000", "0.000");
        UUID lotId = insertLot(fixture, "RESERVED", null);
        insertLotBalance(fixture, lotId, fixture.locationId(), "2.000", "1.000");

        assertCode("INSUFFICIENT_TRACEABLE_LOT_STOCK", () -> adjustmentService.adjust(request(
                fixture, fixture.locationId(), InventoryAdjustmentType.out, "2.000",
                lotId, null, null, null)));

        assertThat(balance(fixture, fixture.locationId())).isEqualByComparingTo("5.000");
        assertThat(lotBalance(fixture, lotId, fixture.locationId())).isEqualByComparingTo("2.000");
        assertThat(count("inventory_movements", fixture.tenantId(), fixture.productId())).isZero();
    }

    @Test
    void serialInCreatesUnitsAndSerialOutWritesOffExactUnitsWithSerialOnlyTraces() {
        Fixture fixture = createFixture(false, false, true);
        authenticate(fixture);

        adjustmentService.adjust(request(
                fixture, fixture.locationId(), InventoryAdjustmentType.in, "2.000",
                null, null, null, List.of("SER-002", "SER-001")));
        adjustmentService.adjust(request(
                fixture, fixture.locationId(), InventoryAdjustmentType.out, "2.000",
                null, null, null, List.of("SER-001", "SER-002")));

        assertThat(serialStatuses(fixture)).containsExactly("WRITTEN_OFF", "WRITTEN_OFF");
        assertThat(balance(fixture, fixture.locationId())).isEqualByComparingTo("0.000");
        assertThat(count("inventory_movements", fixture.tenantId(), fixture.productId())).isEqualTo(2);
        assertThat(count("inventory_movement_traces", fixture.tenantId(), null)).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM inventory_movement_traces WHERE tenant_id = ? AND lot_id IS NULL AND serial_id IS NOT NULL AND quantity = 1",
                        Long.class, fixture.tenantId()))
                .isEqualTo(4L);
    }

    @Test
    void serialRequestRejectsDuplicatesAndQuantityMismatchBeforeWriting() {
        Fixture fixture = createFixture(false, false, true);
        authenticate(fixture);

        assertCode("DUPLICATE_SERIAL", () -> adjustmentService.adjust(request(
                fixture, fixture.locationId(), InventoryAdjustmentType.in, "2.000",
                null, null, null, List.of("DUP", "DUP"))));
        assertCode("SERIAL_COUNT_MISMATCH", () -> adjustmentService.adjust(request(
                fixture, fixture.locationId(), InventoryAdjustmentType.in, "2.000",
                null, null, null, List.of("ONLY-ONE"))));

        assertThat(count("inventory_serials", fixture.tenantId(), fixture.productId())).isZero();
        assertThat(count("inventory_balances", fixture.tenantId(), fixture.productId())).isZero();
        assertThat(count("inventory_movements", fixture.tenantId(), fixture.productId())).isZero();
    }

    @Test
    void existingSerialConflictRollsBackAggregateLotAndMovement() {
        Fixture fixture = createFixture(false, false, true);
        authenticate(fixture);
        adjustmentService.adjust(request(
                fixture, fixture.locationId(), InventoryAdjustmentType.in, "1.000",
                null, null, null, List.of("EXISTING")));

        assertCode("DUPLICATE_SERIAL", () -> adjustmentService.adjust(request(
                fixture, fixture.locationId(), InventoryAdjustmentType.in, "1.000",
                null, null, null, List.of("EXISTING"))));

        assertThat(balance(fixture, fixture.locationId())).isEqualByComparingTo("1.000");
        assertThat(count("inventory_serials", fixture.tenantId(), fixture.productId())).isOne();
        assertThat(count("inventory_movements", fixture.tenantId(), fixture.productId())).isOne();
    }

    @Test
    void serialOutRejectsMissingUnavailableAndWrongLocationWithoutAggregateMutation() {
        Fixture fixture = createFixture(false, false, true);
        authenticate(fixture);
        adjustmentService.adjust(request(
                fixture, fixture.locationId(), InventoryAdjustmentType.in, "1.000",
                null, null, null, List.of("LOC-SERIAL")));
        insertBalance(fixture, fixture.secondLocationId(), "1.000", "0.000");

        assertCode("SERIAL_LOCATION_MISMATCH", () -> adjustmentService.adjust(request(
                fixture, fixture.secondLocationId(), InventoryAdjustmentType.out, "1.000",
                null, null, null, List.of("LOC-SERIAL"))));
        assertCode("SERIAL_NOT_FOUND", () -> adjustmentService.adjust(request(
                fixture, fixture.locationId(), InventoryAdjustmentType.out, "1.000",
                null, null, null, List.of("MISSING"))));
        jdbcTemplate.update(
                "UPDATE inventory_serials SET status = 'RESERVED' WHERE tenant_id = ? AND product_id = ?",
                fixture.tenantId(), fixture.productId());
        assertCode("SERIAL_UNAVAILABLE", () -> adjustmentService.adjust(request(
                fixture, fixture.locationId(), InventoryAdjustmentType.out, "1.000",
                null, null, null, List.of("LOC-SERIAL"))));

        assertThat(balance(fixture, fixture.locationId())).isEqualByComparingTo("1.000");
        assertThat(balance(fixture, fixture.secondLocationId())).isEqualByComparingTo("1.000");
        assertThat(count("inventory_movements", fixture.tenantId(), fixture.productId())).isOne();
    }

    @Test
    void lotAndSerialStayAssociatedAndMismatchingLotRollsBack() {
        Fixture fixture = createFixture(true, false, true);
        authenticate(fixture);
        adjustmentService.adjust(request(
                fixture, fixture.locationId(), InventoryAdjustmentType.in, "1.000",
                null, "LOT-A", null, List.of("SER-A")));
        adjustmentService.adjust(request(
                fixture, fixture.locationId(), InventoryAdjustmentType.in, "1.000",
                null, "LOT-B", null, List.of("SER-B")));
        UUID lotA = lotId(fixture, "LOT-A");
        UUID lotB = lotId(fixture, "LOT-B");

        assertCode("SERIAL_LOT_MISMATCH", () -> adjustmentService.adjust(request(
                fixture, fixture.locationId(), InventoryAdjustmentType.out, "1.000",
                lotB, null, null, List.of("SER-A"))));
        adjustmentService.adjust(request(
                fixture, fixture.locationId(), InventoryAdjustmentType.out, "1.000",
                lotA, null, null, List.of("SER-A")));

        assertThat(balance(fixture, fixture.locationId())).isEqualByComparingTo("1.000");
        assertThat(lotBalance(fixture, lotA, fixture.locationId())).isEqualByComparingTo("0.000");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT lot_id FROM inventory_serials WHERE tenant_id = ? AND serial_number = 'SER-A'",
                        UUID.class, fixture.tenantId()))
                .isEqualTo(lotA);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM inventory_movement_traces WHERE tenant_id = ? AND lot_id IS NOT NULL",
                        Long.class, fixture.tenantId()))
                .isZero();
        assertThat(count("inventory_movement_traces", fixture.tenantId(), null)).isEqualTo(3);
    }

    @Test
    void crossTenantLotAndSerialAreNotVisibleToAdjustment() {
        Fixture tenantA = createFixture(true, false, true);
        Fixture tenantB = createFixture(true, false, true);
        UUID foreignLot = insertLot(tenantB, "FOREIGN", null);
        insertBalance(tenantA, tenantA.locationId(), "2.000", "0.000");
        insertSerial(tenantB, foreignLot, tenantB.locationId(), "FOREIGN-SERIAL", "AVAILABLE");
        UUID localLot = insertLot(tenantA, "LOCAL", null);
        insertLotBalance(tenantA, localLot, tenantA.locationId(), "1.000", "0.000");
        authenticate(tenantA);

        assertCode("LOT_NOT_FOUND", () -> adjustmentService.adjust(request(
                tenantA, tenantA.locationId(), InventoryAdjustmentType.out, "1.000",
                foreignLot, null, null, List.of("FOREIGN-SERIAL"))));
        assertCode("SERIAL_NOT_FOUND", () -> adjustmentService.adjust(request(
                tenantA, tenantA.locationId(), InventoryAdjustmentType.out, "1.000",
                localLot, null, null, List.of("FOREIGN-SERIAL"))));

        assertThat(balance(tenantA, tenantA.locationId())).isEqualByComparingTo("2.000");
        assertThat(count("inventory_movements", tenantA.tenantId(), tenantA.productId())).isZero();
    }

    @Test
    void unauthorizedBranchIsRejectedBeforeAnyStockWrite() {
        Fixture fixture = createFixture(false, false, false);
        authenticate(fixture);
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(false, Set.of()));

        assertCode("BRANCH_ACCESS_DENIED", () -> adjustmentService.adjust(request(
                fixture, fixture.locationId(), InventoryAdjustmentType.in, "1.000",
                null, null, null, null)));

        assertThat(count("inventory_balances", fixture.tenantId(), fixture.productId())).isZero();
        assertThat(count("inventory_movements", fixture.tenantId(), fixture.productId())).isZero();
    }

    @Test
    void availableLookupsFilterByLocationStatusProductAndLot() {
        Fixture fixture = createFixture(true, false, true);
        authenticate(fixture);
        adjustmentService.adjust(request(
                fixture, fixture.locationId(), InventoryAdjustmentType.in, "2.000",
                null, "LOOKUP", null, List.of("LOOK-2", "LOOK-1")));
        UUID lotId = lotId(fixture, "LOOKUP");

        List<InventoryLotAvailabilityDto> lots = queryService.availableLots(
                fixture.branchId(), fixture.productId(), fixture.locationId());
        List<InventorySerialAvailabilityDto> serials = queryService.availableSerials(
                fixture.branchId(), fixture.productId(), fixture.locationId(), lotId);

        assertThat(lots).singleElement().satisfies(lot -> {
            assertThat(lot.lotId()).isEqualTo(lotId);
            assertThat(lot.quantity()).isEqualByComparingTo("2.000");
            assertThat(lot.availableQuantity()).isEqualByComparingTo("2.000");
            assertThat(lot.locationId()).isEqualTo(fixture.locationId());
        });
        assertThat(serials).extracting(InventorySerialAvailabilityDto::serialNumber)
                .containsExactly("LOOK-1", "LOOK-2");

        jdbcTemplate.update(
                "UPDATE inventory_serials SET status = 'WRITTEN_OFF' WHERE tenant_id = ? AND serial_number = 'LOOK-1'",
                fixture.tenantId());
        assertThat(queryService.availableSerials(
                        fixture.branchId(), fixture.productId(), fixture.locationId(), lotId))
                .extracting(InventorySerialAvailabilityDto::serialNumber)
                .containsExactly("LOOK-2");
    }

    @Test
    void lookupsRejectCrossTenantBranchProductLotAndUnauthorizedBranch() {
        Fixture tenantA = createFixture(true, false, true);
        Fixture tenantB = createFixture(true, false, true);
        UUID foreignLot = insertLot(tenantB, "FOREIGN-LOOKUP", null);
        authenticate(tenantA);

        assertCode("BRANCH_NOT_FOUND", () -> queryService.availableLots(
                tenantB.branchId(), tenantA.productId(), null));
        assertCode("PRODUCT_NOT_FOUND", () -> queryService.availableLots(
                tenantA.branchId(), tenantB.productId(), null));
        assertCode("LOT_NOT_FOUND", () -> queryService.availableSerials(
                tenantA.branchId(), tenantA.productId(), null, foreignLot));

        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(false, Set.of()));
        assertCode("BRANCH_ACCESS_DENIED", () -> queryService.availableLots(
                tenantA.branchId(), tenantA.productId(), null));
    }

    private Fixture createFixture(boolean trackingLot, boolean trackingExpiration, boolean trackingSerial) {
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID secondBranchId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        UUID secondLocationId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, slug, status, default_currency, timezone) VALUES (?, ?, ?, 'active', 'GTQ', 'America/Guatemala')",
                tenantId, "Tenant " + suffix, "tenant-" + suffix);
        insertBranch(tenantId, branchId, "MAIN-" + suffix);
        insertBranch(tenantId, secondBranchId, "SECOND-" + suffix);
        jdbcTemplate.update(
                "INSERT INTO categories (id, tenant_id, name, slug, status) VALUES (?, ?, ?, ?, 'active')",
                categoryId, tenantId, "Categoria " + suffix, "categoria-" + suffix);
        jdbcTemplate.update(
                "INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status) VALUES (?, ?, ?, 'Unidad', 'und', 'unit', true, 'active')",
                unitId, tenantId, "U-" + suffix.substring(0, 8));
        jdbcTemplate.update(
                """
                INSERT INTO products
                    (id, tenant_id, sku, name, product_type, category_id, base_unit_id,
                     tracking_stock, tracking_lot, tracking_expiration, tracking_serial)
                VALUES (?, ?, ?, ?, 'physical', ?, ?, true, ?, ?, ?)
                """,
                productId, tenantId, "SKU-" + suffix, "Producto " + suffix,
                categoryId, unitId, trackingLot, trackingExpiration, trackingSerial);
        insertLocation(tenantId, branchId, locationId, "LOC-A-" + suffix);
        insertLocation(tenantId, branchId, secondLocationId, "LOC-B-" + suffix);
        jdbcTemplate.update(
                "INSERT INTO users (id, tenant_id, name, email, type, status, branch_id) VALUES (?, ?, ?, ?, 'employee', 'active', ?)",
                userId, tenantId, "Usuario " + suffix, "user-" + suffix + "@test.local", branchId);
        return new Fixture(
                tenantId, branchId, secondBranchId, productId, userId, locationId, secondLocationId);
    }

    private void authenticate(Fixture fixture) {
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                fixture.userId(), fixture.tenantId(), UserType.employee, null,
                fixture.branchId(), UUID.randomUUID()));
    }

    private void insertBranch(UUID tenantId, UUID branchId, String code) {
        jdbcTemplate.update(
                "INSERT INTO branches (id, tenant_id, code, name, type, status) VALUES (?, ?, ?, ?, 'main', 'active')",
                branchId, tenantId, code, "Sucursal " + code);
    }

    private void insertLocation(UUID tenantId, UUID branchId, UUID locationId, String code) {
        jdbcTemplate.update(
                "INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status) VALUES (?, ?, ?, ?, ?, 'warehouse', 'active')",
                locationId, tenantId, branchId, code, "Ubicacion " + code);
    }

    private InventoryAdjustmentRequest request(
            Fixture fixture,
            UUID locationId,
            InventoryAdjustmentType type,
            String quantity,
            UUID lotId,
            String lotNumber,
            LocalDate expirationDate,
            List<String> serialNumbers) {
        return new InventoryAdjustmentRequest(
                fixture.branchId(), fixture.productId(), type, new BigDecimal(quantity),
                "Ajuste de prueba", "MANUAL_ADJUSTMENT", UUID.randomUUID(), locationId,
                lotId, lotNumber, expirationDate, serialNumbers);
    }

    private InventoryAdjustmentRequest requestWithExpected(
            Fixture fixture,
            UUID locationId,
            InventoryAdjustmentType type,
            String quantity,
            String expectedQuantity) {
        return new InventoryAdjustmentRequest(
                fixture.branchId(), fixture.productId(), type, new BigDecimal(quantity),
                "Ajuste con precondicion", "count_correction", UUID.randomUUID(), locationId,
                null, null, null, List.of(), new BigDecimal(expectedQuantity));
    }

    private UUID insertLot(Fixture fixture, String lotNumber, LocalDate expirationDate) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO inventory_lots (id, tenant_id, product_id, lot_number, expiration_date) VALUES (?, ?, ?, ?, ?)",
                id, fixture.tenantId(), fixture.productId(), lotNumber, expirationDate);
        return id;
    }

    private void insertBalance(Fixture fixture, UUID locationId, String quantity, String reserved) {
        jdbcTemplate.update(
                "INSERT INTO inventory_balances (id, tenant_id, branch_id, product_id, location_id, quantity, reserved_quantity) VALUES (?, ?, ?, ?, ?, ?::numeric, ?::numeric)",
                UUID.randomUUID(), fixture.tenantId(), fixture.branchId(), fixture.productId(),
                locationId, quantity, reserved);
    }

    private void insertLotBalance(
            Fixture fixture, UUID lotId, UUID locationId, String quantity, String reserved) {
        jdbcTemplate.update(
                "INSERT INTO inventory_lot_balances (id, tenant_id, branch_id, location_id, lot_id, quantity, reserved_quantity) VALUES (?, ?, ?, ?, ?, ?::numeric, ?::numeric)",
                UUID.randomUUID(), fixture.tenantId(), fixture.branchId(), locationId,
                lotId, quantity, reserved);
    }

    private void insertSerial(
            Fixture fixture, UUID lotId, UUID locationId, String serialNumber, String status) {
        jdbcTemplate.update(
                "INSERT INTO inventory_serials (id, tenant_id, branch_id, location_id, product_id, serial_number, lot_id, status, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)",
                UUID.randomUUID(), fixture.tenantId(), fixture.branchId(), locationId,
                fixture.productId(), serialNumber, lotId, status);
    }

    private UUID lotId(Fixture fixture, String lotNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM inventory_lots WHERE tenant_id = ? AND product_id = ? AND lot_number = ?",
                UUID.class, fixture.tenantId(), fixture.productId(), lotNumber);
    }

    private BigDecimal balance(Fixture fixture, UUID locationId) {
        return jdbcTemplate.queryForObject(
                "SELECT quantity FROM inventory_balances WHERE tenant_id = ? AND branch_id = ? AND product_id = ? AND location_id = ?",
                BigDecimal.class, fixture.tenantId(), fixture.branchId(), fixture.productId(), locationId);
    }

    private BigDecimal lotBalance(Fixture fixture, UUID lotId, UUID locationId) {
        return jdbcTemplate.queryForObject(
                "SELECT quantity FROM inventory_lot_balances WHERE tenant_id = ? AND branch_id = ? AND lot_id = ? AND location_id = ?",
                BigDecimal.class, fixture.tenantId(), fixture.branchId(), lotId, locationId);
    }

    private List<String> serialStatuses(Fixture fixture) {
        return jdbcTemplate.queryForList(
                "SELECT status FROM inventory_serials WHERE tenant_id = ? AND product_id = ? ORDER BY serial_number",
                String.class, fixture.tenantId(), fixture.productId());
    }

    private long count(String table, UUID tenantId, UUID productId) {
        String productPredicate = productId == null ? "" : " AND product_id = ?";
        Long result = productId == null
                ? jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Long.class, tenantId)
                : jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM " + table + " WHERE tenant_id = ?" + productPredicate,
                        Long.class, tenantId, productId);
        return result == null ? 0 : result;
    }

    private static void assertCode(String expected, ThrowingOperation operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(expected);
    }

    private record Fixture(
            UUID tenantId,
            UUID branchId,
            UUID secondBranchId,
            UUID productId,
            UUID userId,
            UUID locationId,
            UUID secondLocationId) {}

    @FunctionalInterface
    private interface ThrowingOperation {
        void run();
    }
}

package com.omniretail.backend.pos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.catalog.dto.ResolvedProductPrice;
import com.omniretail.backend.catalog.service.ProductPriceResolver;
import com.omniretail.backend.pos.dto.CreateSaleRequest;
import com.omniretail.backend.pos.dto.CreateSaleReturnRequest;
import com.omniretail.backend.pos.dto.InventoryTrackingSelectionRequest;
import com.omniretail.backend.pos.dto.SaleConfirmationResponse;
import com.omniretail.backend.pos.entity.PaymentMethod;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.math.BigDecimal;
import java.time.Instant;
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
class PosTraceabilityServiceTest {

    @Autowired private SaleService sales;
    @Autowired private SaleReturnService returns;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;
    @MockitoBean private TenantCapabilityGuard capabilityGuard;
    @MockitoBean private BranchAccessResolver branchAccess;
    @MockitoBean private ProductPriceResolver priceResolver;
    private final ThreadLocal<AuthenticatedUser> concurrentActor = new ThreadLocal<>();
    private AuthenticatedUser defaultActor;

    @BeforeEach
    void setUp() {
        given(currentUser.require()).willAnswer(invocation -> {
            AuthenticatedUser actor = concurrentActor.get();
            return actor == null ? defaultActor : actor;
        });
        given(branchAccess.resolve(any(AuthenticatedUser.class))).willAnswer(invocation -> {
            AuthenticatedUser actor = invocation.getArgument(0);
            return new BranchAccessResolver.BranchAccess(false, Set.of(actor.branchId()));
        });
        given(priceResolver.resolveEffectivePrice(
                        any(), any(), any(Instant.class), eq("pos"), any(), any(BigDecimal.class)))
                .willReturn(new ResolvedProductPrice(
                        new BigDecimal("10.00"),
                        new BigDecimal("10.00"),
                        BigDecimal.ZERO.setScale(2),
                        null));
    }

    @Test
    void multiLotSaleCreatesOneMovementAndVoidRestoresExactLotsAndLocation() {
        Fixture fixture = fixture(true, false, new BigDecimal("10.000"), List.of());
        UUID secondLot = addLot(fixture, "LOT-B", new BigDecimal("4.000"));
        jdbc.update(
                "UPDATE inventory_lot_balances SET quantity = 6.000 WHERE lot_id = ?",
                fixture.lotId());
        SaleConfirmationResponse sale = sales.create(saleRequest(
                fixture,
                fixture.shiftId(),
                "10.000",
                UUID.randomUUID(),
                List.of(
                        selection(fixture, fixture.lotId(), "6.000", List.of()),
                        selection(fixture, secondLot, "4.000", List.of()))));
        UUID saleItemId = saleItemId(sale.id());

        assertThat(balance(fixture)).isEqualByComparingTo("0.000");
        assertThat(movementCount(fixture, "POS_SALE", sale.id())).isOne();
        assertThat(jdbc.queryForObject(
                        "SELECT reference_line_id FROM inventory_movements WHERE reference_type = 'POS_SALE' AND reference_id = ?",
                        UUID.class,
                        sale.id()))
                .isEqualTo(saleItemId);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM inventory_movement_traces t JOIN inventory_movements m ON m.id = t.movement_id WHERE m.reference_id = ?",
                        Long.class,
                        sale.id()))
                .isEqualTo(2L);
        assertThat(sales.get(sale.id()).items().getFirst().trackingDetails()).hasSize(2);

        sales.voidSale(sale.id());

        assertThat(balance(fixture)).isEqualByComparingTo("10.000");
        assertThat(jdbc.queryForObject(
                        "SELECT sum(quantity) FROM inventory_lot_balances WHERE tenant_id = ? AND location_id = ?",
                        BigDecimal.class,
                        fixture.tenantId(),
                        fixture.locationId()))
                .isEqualByComparingTo("10.000");
        assertThat(movementCount(fixture, "POS_SALE_VOID", sale.id())).isOne();
        assertThat(jdbc.queryForObject(
                        "SELECT from_location_id FROM inventory_movements WHERE reference_type = 'POS_SALE' AND reference_id = ?",
                        UUID.class,
                        sale.id()))
                .isEqualTo(fixture.locationId());
    }

    @Test
    void convertedLotSaleUsesPhysicalQuantityAndVoidRestoresTheSameTrace() {
        Fixture fixture = fixture(true, false, new BigDecimal("24.000"), List.of());
        configureSaleUnitConversion(fixture, "12.000000");

        SaleConfirmationResponse sale = sales.create(saleRequest(
                fixture,
                fixture.shiftId(),
                "2.000",
                UUID.randomUUID(),
                List.of(selection(fixture, fixture.lotId(), "24.000", List.of()))));
        UUID saleItemId = saleItemId(sale.id());

        assertThat(jdbc.queryForObject(
                        "SELECT quantity FROM sale_items WHERE id = ?",
                        BigDecimal.class,
                        saleItemId))
                .isEqualByComparingTo("2.000");
        assertThat(jdbc.queryForObject(
                        "SELECT sum(quantity) FROM inventory_movements WHERE reference_type = 'POS_SALE' AND reference_id = ?",
                        BigDecimal.class,
                        sale.id()))
                .isEqualByComparingTo("24.000");
        assertThat(sale.items().getFirst().quantity()).isEqualByComparingTo("2.000");
        assertThat(sale.items().getFirst().trackingDetails())
                .singleElement()
                .satisfies(detail -> assertThat(detail.quantity()).isEqualByComparingTo("24.000"));

        sales.voidSale(sale.id());

        assertThat(balance(fixture)).isEqualByComparingTo("24.000");
        assertThat(jdbc.queryForObject(
                        "SELECT quantity FROM inventory_lot_balances WHERE tenant_id = ? AND location_id = ? AND lot_id = ?",
                        BigDecimal.class,
                        fixture.tenantId(),
                        fixture.locationId(),
                        fixture.lotId()))
                .isEqualByComparingTo("24.000");
        assertThat(jdbc.queryForObject(
                        "SELECT sum(t.quantity) FROM inventory_movement_traces t JOIN inventory_movements m ON m.id = t.movement_id WHERE m.reference_type = 'POS_SALE_VOID' AND m.reference_id = ? AND t.lot_id = ?",
                        BigDecimal.class,
                        sale.id(),
                        fixture.lotId()))
                .isEqualByComparingTo("24.000");
    }

    @Test
    void convertedLotSaleRejectsCommercialQuantityAsPhysicalSelection() {
        Fixture fixture = fixture(true, false, new BigDecimal("24.000"), List.of());
        configureSaleUnitConversion(fixture, "12.000000");

        assertThatThrownBy(() -> sales.create(saleRequest(
                        fixture,
                        fixture.shiftId(),
                        "2.000",
                        UUID.randomUUID(),
                        List.of(selection(fixture, fixture.lotId(), "2.000", List.of())))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("TRACKING_QUANTITY_MISMATCH"));

        assertThat(balance(fixture)).isEqualByComparingTo("24.000");
    }

    @Test
    void convertedLotReturnRestoresPhysicalProportion() {
        Fixture fixture = fixture(true, false, new BigDecimal("24.000"), List.of());
        configureSaleUnitConversion(fixture, "12.000000");
        SaleConfirmationResponse sale = sales.create(saleRequest(
                fixture,
                fixture.shiftId(),
                "2.000",
                UUID.randomUUID(),
                List.of(selection(fixture, fixture.lotId(), "24.000", List.of()))));
        UUID saleItemId = saleItemId(sale.id());

        var returned = returns.create(
                sale.id(),
                new CreateSaleReturnRequest(
                        "Devolucion de una caja",
                        List.of(new CreateSaleReturnRequest.Line(
                                saleItemId,
                                BigDecimal.ONE,
                                List.of(selection(
                                        fixture, fixture.lotId(), "12.000", List.of()))))));

        assertThat(returned.lines().getFirst().quantity()).isEqualByComparingTo("1.000");
        assertThat(returned.lines().getFirst().trackingDetails())
                .singleElement()
                .satisfies(detail -> assertThat(detail.quantity()).isEqualByComparingTo("12.000"));
        assertThat(jdbc.queryForObject(
                        "SELECT sum(quantity) FROM inventory_movements WHERE reference_type = 'POS_SALE_RETURN' AND reference_id = ?",
                        BigDecimal.class,
                        returned.id()))
                .isEqualByComparingTo("12.000");
        assertThat(balance(fixture)).isEqualByComparingTo("12.000");
    }

    @Test
    void serialSaleAndPartialReturnRestoreOnlyTheExactSoldSerialOnce() {
        Fixture fixture = fixture(
                false, true, new BigDecimal("2.000"), List.of("SER-1", "SER-2"));
        SaleConfirmationResponse sale = sales.create(saleRequest(
                fixture,
                fixture.shiftId(),
                "2.000",
                UUID.randomUUID(),
                List.of(selection(fixture, null, "2.000", List.of("SER-2", "SER-1")))));
        UUID saleItemId = saleItemId(sale.id());

        assertThat(serialStatus(fixture, "SER-1")).isEqualTo("CONSUMED");
        assertThat(serialStatus(fixture, "SER-2")).isEqualTo("CONSUMED");
        var returned = returns.create(
                sale.id(),
                new CreateSaleReturnRequest(
                        "Devolucion parcial",
                        List.of(new CreateSaleReturnRequest.Line(
                                saleItemId,
                                BigDecimal.ONE,
                                List.of(selection(fixture, null, "1.000", List.of("SER-1")))))));

        assertThat(serialStatus(fixture, "SER-1")).isEqualTo("AVAILABLE");
        assertThat(serialStatus(fixture, "SER-2")).isEqualTo("CONSUMED");
        assertThat(returned.lines().getFirst().trackingDetails())
                .singleElement()
                .satisfies(detail -> assertThat(detail.serialNumbers()).containsExactly("SER-1"));
        assertThat(movementCount(fixture, "POS_SALE_RETURN", returned.id())).isOne();

        assertThatThrownBy(() -> returns.create(
                        sale.id(),
                        new CreateSaleReturnRequest(
                                "Intento repetido",
                                List.of(new CreateSaleReturnRequest.Line(
                                        saleItemId,
                                        BigDecimal.ONE,
                                        List.of(selection(
                                                fixture, null, "1.000", List.of("SER-1"))))))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("SERIAL_NOT_RETURNABLE"));
    }

    @Test
    void lotAndSerialUseOnlySerialTracesAndReturnRestoresTheExactSubledger() {
        Fixture fixture = fixture(
                true, true, new BigDecimal("2.000"), List.of("LOT-SER-1", "LOT-SER-2"));
        SaleConfirmationResponse sale = sales.create(saleRequest(
                fixture,
                fixture.shiftId(),
                "2.000",
                UUID.randomUUID(),
                List.of(selection(
                        fixture,
                        fixture.lotId(),
                        "2.000",
                        List.of("LOT-SER-1", "LOT-SER-2")))));
        UUID saleItemId = saleItemId(sale.id());

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM inventory_movement_traces t JOIN inventory_movements m ON m.id = t.movement_id WHERE m.reference_id = ? AND t.serial_id IS NOT NULL AND t.lot_id IS NULL",
                        Long.class,
                        sale.id()))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                        "SELECT quantity FROM inventory_lot_balances WHERE lot_id = ?",
                        BigDecimal.class,
                        fixture.lotId()))
                .isEqualByComparingTo("0.000");

        var returned = returns.create(
                sale.id(),
                new CreateSaleReturnRequest(
                        "Devolucion serializada",
                        List.of(new CreateSaleReturnRequest.Line(
                                saleItemId,
                                BigDecimal.ONE,
                                List.of(selection(
                                        fixture,
                                        fixture.lotId(),
                                        "1.000",
                                        List.of("LOT-SER-2")))))));

        UUID returnItemId = returned.lines().getFirst().id();
        assertThat(serialStatus(fixture, "LOT-SER-1")).isEqualTo("CONSUMED");
        assertThat(serialStatus(fixture, "LOT-SER-2")).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject(
                        "SELECT quantity FROM inventory_lot_balances WHERE lot_id = ?",
                        BigDecimal.class,
                        fixture.lotId()))
                .isEqualByComparingTo("1.000");
        assertThat(jdbc.queryForObject(
                        "SELECT reference_line_id FROM inventory_movements WHERE reference_type = 'POS_SALE_RETURN' AND reference_id = ?",
                        UUID.class,
                        returned.id()))
                .isEqualTo(returnItemId);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM inventory_movement_traces t JOIN inventory_movements m ON m.id = t.movement_id WHERE m.reference_id = ? AND t.serial_id IS NOT NULL AND t.lot_id IS NULL",
                        Long.class,
                        returned.id()))
                .isOne();
    }

    @Test
    void confirmationFingerprintRejectsChangingTheSelectedSerial() {
        Fixture fixture = fixture(
                false, true, new BigDecimal("2.000"), List.of("FP-1", "FP-2"));
        UUID confirmationId = UUID.randomUUID();
        sales.create(saleRequest(
                fixture,
                fixture.shiftId(),
                "1.000",
                confirmationId,
                List.of(selection(fixture, null, "1.000", List.of("FP-1")))));

        assertThatThrownBy(() -> sales.create(saleRequest(
                        fixture,
                        fixture.shiftId(),
                        "1.000",
                        confirmationId,
                        List.of(selection(fixture, null, "1.000", List.of("FP-2"))))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void expiredLotAndForeignLocationAreRejectedWithoutPartialSale() {
        Fixture fixture = fixture(true, false, BigDecimal.ONE, List.of());
        jdbc.update(
                "UPDATE products SET tracking_expiration = true WHERE id = ?",
                fixture.productId());
        jdbc.update(
                "UPDATE inventory_lots SET expiration_date = current_date - 1 WHERE id = ?",
                fixture.lotId());

        assertThatThrownBy(() -> sales.create(saleRequest(
                        fixture,
                        fixture.shiftId(),
                        "1.000",
                        UUID.randomUUID(),
                        List.of(selection(
                                fixture, fixture.lotId(), "1.000", List.of())))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("LOT_EXPIRED"));
        assertThat(balance(fixture)).isEqualByComparingTo("1.000");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM sales WHERE tenant_id = ?",
                        Long.class,
                        fixture.tenantId()))
                .isZero();

        jdbc.update(
                "UPDATE inventory_lots SET expiration_date = current_date + 30 WHERE id = ?",
                fixture.lotId());
        InventoryTrackingSelectionRequest foreignLocation = new InventoryTrackingSelectionRequest(
                fixture.productId(),
                UUID.randomUUID(),
                fixture.lotId(),
                BigDecimal.ONE,
                List.of());
        assertThatThrownBy(() -> sales.create(saleRequest(
                        fixture,
                        fixture.shiftId(),
                        "1.000",
                        UUID.randomUUID(),
                        List.of(foreignLocation))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("LOCATION_NOT_FOUND"));
        assertThat(balance(fixture)).isEqualByComparingTo("1.000");
    }

    @Test
    void lotFromAnotherProductIsHiddenAndCannotCreateAPartialSale() {
        Fixture fixture = fixture(true, false, BigDecimal.ONE, List.of());
        UUID foreignProduct = addInventoryProduct(fixture, true, false);
        UUID foreignLot = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO inventory_lots (id, tenant_id, product_id, lot_number) VALUES (?, ?, ?, 'FOREIGN-LOT')",
                foreignLot,
                fixture.tenantId(),
                foreignProduct);

        assertThatThrownBy(() -> sales.create(saleRequest(
                        fixture,
                        fixture.shiftId(),
                        "1.000",
                        UUID.randomUUID(),
                        List.of(selection(fixture, foreignLot, "1.000", List.of())))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("LOT_NOT_FOUND"));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM sales WHERE tenant_id = ?",
                        Long.class,
                        fixture.tenantId()))
                .isZero();
        assertThat(balance(fixture)).isEqualByComparingTo("1.000");
    }

    @Test
    void serialFromAnotherProductIsHiddenAndCannotCreateAPartialSale() {
        Fixture fixture = fixture(false, true, BigDecimal.ONE, List.of());
        UUID foreignProduct = addInventoryProduct(fixture, false, true);
        jdbc.update(
                "INSERT INTO inventory_serials (id, tenant_id, branch_id, location_id, product_id, serial_number, status, version) VALUES (?, ?, ?, ?, ?, 'FOREIGN-SERIAL', 'AVAILABLE', 0)",
                UUID.randomUUID(),
                fixture.tenantId(),
                fixture.branchId(),
                fixture.locationId(),
                foreignProduct);

        assertThatThrownBy(() -> sales.create(saleRequest(
                        fixture,
                        fixture.shiftId(),
                        "1.000",
                        UUID.randomUUID(),
                        List.of(selection(
                                fixture, null, "1.000", List.of("FOREIGN-SERIAL"))))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("SERIAL_NOT_FOUND"));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM sales WHERE tenant_id = ?",
                        Long.class,
                        fixture.tenantId()))
                .isZero();
        assertThat(balance(fixture)).isEqualByComparingTo("1.000");
    }

    @Test
    void duplicateSerialAcrossSelectionsIsRejectedAtomically() {
        Fixture fixture = fixture(
                false, true, new BigDecimal("2.000"), List.of("DUPLICATE-SERIAL"));

        assertThatThrownBy(() -> sales.create(saleRequest(
                        fixture,
                        fixture.shiftId(),
                        "2.000",
                        UUID.randomUUID(),
                        List.of(
                                selection(
                                        fixture,
                                        null,
                                        "1.000",
                                        List.of("DUPLICATE-SERIAL")),
                                selection(
                                        fixture,
                                        null,
                                        "1.000",
                                        List.of("DUPLICATE-SERIAL"))))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("DUPLICATE_SERIAL"));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM sales WHERE tenant_id = ?",
                        Long.class,
                        fixture.tenantId()))
                .isZero();
        assertThat(balance(fixture)).isEqualByComparingTo("2.000");
        assertThat(serialStatus(fixture, "DUPLICATE-SERIAL")).isEqualTo("AVAILABLE");
    }

    @Test
    void partialLotReturnsCannotReuseAnExhaustedSoldLot() {
        Fixture fixture = fixture(true, false, new BigDecimal("4.000"), List.of());
        UUID secondLot = addLot(fixture, "LOT-B", new BigDecimal("2.000"));
        jdbc.update(
                "UPDATE inventory_lot_balances SET quantity = 2.000 WHERE lot_id = ?",
                fixture.lotId());
        SaleConfirmationResponse sale = sales.create(saleRequest(
                fixture,
                fixture.shiftId(),
                "4.000",
                UUID.randomUUID(),
                List.of(
                        selection(fixture, fixture.lotId(), "2.000", List.of()),
                        selection(fixture, secondLot, "2.000", List.of()))));
        UUID saleItemId = saleItemId(sale.id());
        returns.create(
                sale.id(),
                new CreateSaleReturnRequest(
                        "Primer lote",
                        List.of(new CreateSaleReturnRequest.Line(
                                saleItemId,
                                new BigDecimal("2.000"),
                                List.of(selection(
                                        fixture, fixture.lotId(), "2.000", List.of()))))));

        assertThatThrownBy(() -> returns.create(
                        sale.id(),
                        new CreateSaleReturnRequest(
                                "Lote repetido",
                                List.of(new CreateSaleReturnRequest.Line(
                                        saleItemId,
                                        new BigDecimal("2.000"),
                                        List.of(selection(
                                                fixture,
                                                fixture.lotId(),
                                                "2.000",
                                                List.of())))))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("RETURN_TRACE_QUANTITY_EXCEEDED"));
        assertThat(jdbc.queryForObject(
                        "SELECT quantity FROM inventory_lot_balances WHERE lot_id = ?",
                        BigDecimal.class,
                        fixture.lotId()))
                .isEqualByComparingTo("2.000");
        assertThat(jdbc.queryForObject(
                        "SELECT quantity FROM inventory_lot_balances WHERE lot_id = ?",
                        BigDecimal.class,
                        secondLot))
                .isEqualByComparingTo("0.000");
    }

    @Test
    void voidRollsBackWhenOriginalSerialIsNoLongerConsumed() {
        Fixture fixture = fixture(false, true, BigDecimal.ONE, List.of("VOID-STATE"));
        SaleConfirmationResponse sale = sales.create(saleRequest(
                fixture,
                fixture.shiftId(),
                "1.000",
                UUID.randomUUID(),
                List.of(selection(
                        fixture, null, "1.000", List.of("VOID-STATE")))));
        jdbc.update(
                "UPDATE inventory_serials SET status = 'WRITTEN_OFF' WHERE tenant_id = ? AND serial_number = 'VOID-STATE'",
                fixture.tenantId());

        assertThatThrownBy(() -> sales.voidSale(sale.id()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("SERIAL_STATUS_CONFLICT"));
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM sales WHERE id = ?",
                        String.class,
                        sale.id()))
                .isEqualTo("completed");
        assertThat(balance(fixture)).isEqualByComparingTo("0.000");
        assertThat(movementCount(fixture, "POS_SALE_VOID", sale.id())).isZero();
    }

    @Test
    void concurrentSalesForSameSerialAllowOnlyOneConsumption() throws Exception {
        Fixture fixture = fixture(false, true, BigDecimal.ONE, List.of("RACE-SERIAL"));
        List<Outcome> outcomes = race(
                asActor(fixture.primaryActor(), () -> sales.create(saleRequest(
                        fixture,
                        fixture.shiftId(),
                        "1.000",
                        UUID.randomUUID(),
                        List.of(selection(
                                fixture, null, "1.000", List.of("RACE-SERIAL")))))),
                asActor(fixture.secondaryActor(), () -> sales.create(saleRequest(
                        fixture,
                        fixture.secondaryShiftId(),
                        "1.000",
                        UUID.randomUUID(),
                        List.of(selection(
                                fixture, null, "1.000", List.of("RACE-SERIAL")))))));

        assertThat(outcomes).filteredOn(Outcome::success).hasSize(1);
        assertThat(serialStatus(fixture, "RACE-SERIAL")).isEqualTo("CONSUMED");
        assertThat(balance(fixture)).isEqualByComparingTo("0.000");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM inventory_movements WHERE tenant_id = ? AND reference_type = 'POS_SALE'",
                        Long.class,
                        fixture.tenantId()))
                .isOne();
    }

    @Test
    void concurrentLotSalesCannotOversellAndConcurrentVoidRestoresOnce() throws Exception {
        Fixture fixture = fixture(true, false, BigDecimal.ONE, List.of());
        List<Outcome> saleOutcomes = race(
                asActor(fixture.primaryActor(), () -> sales.create(saleRequest(
                        fixture,
                        fixture.shiftId(),
                        "1.000",
                        UUID.randomUUID(),
                        List.of(selection(fixture, fixture.lotId(), "1.000", List.of()))))),
                asActor(fixture.secondaryActor(), () -> sales.create(saleRequest(
                        fixture,
                        fixture.secondaryShiftId(),
                        "1.000",
                        UUID.randomUUID(),
                        List.of(selection(fixture, fixture.lotId(), "1.000", List.of()))))));
        assertThat(saleOutcomes).filteredOn(Outcome::success).hasSize(1);
        UUID saleId = jdbc.queryForObject(
                "SELECT id FROM sales WHERE tenant_id = ?",
                UUID.class,
                fixture.tenantId());

        List<Outcome> voidOutcomes = race(
                asActor(fixture.primaryActor(), () -> sales.voidSale(saleId)),
                asActor(fixture.secondaryActor(), () -> sales.voidSale(saleId)));

        assertThat(voidOutcomes).filteredOn(Outcome::success).hasSize(1);
        assertThat(balance(fixture)).isEqualByComparingTo("1.000");
        assertThat(movementCount(fixture, "POS_SALE_VOID", saleId)).isOne();
    }

    @Test
    void concurrentReturnsForSameSerialRestoreItOnlyOnce() throws Exception {
        Fixture fixture = fixture(false, true, BigDecimal.ONE, List.of("RETURN-RACE"));
        SaleConfirmationResponse sale = sales.create(saleRequest(
                fixture,
                fixture.shiftId(),
                "1.000",
                UUID.randomUUID(),
                List.of(selection(
                        fixture, null, "1.000", List.of("RETURN-RACE")))));
        UUID saleItemId = saleItemId(sale.id());
        CreateSaleReturnRequest request = new CreateSaleReturnRequest(
                "Devolucion concurrente",
                List.of(new CreateSaleReturnRequest.Line(
                        saleItemId,
                        BigDecimal.ONE,
                        List.of(selection(
                                fixture, null, "1.000", List.of("RETURN-RACE"))))));

        List<Outcome> outcomes = race(
                asActor(fixture.primaryActor(), () -> returns.create(sale.id(), request)),
                asActor(fixture.secondaryActor(), () -> returns.create(sale.id(), request)));

        assertThat(outcomes).filteredOn(Outcome::success).hasSize(1);
        assertThat(serialStatus(fixture, "RETURN-RACE")).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM inventory_movements WHERE tenant_id = ? AND reference_type = 'POS_SALE_RETURN'",
                        Long.class,
                        fixture.tenantId()))
                .isOne();
    }

    @Test
    void concurrentLotReturnsCannotExceedTheSoldQuantity() throws Exception {
        Fixture fixture = fixture(true, false, BigDecimal.ONE, List.of());
        SaleConfirmationResponse sale = sales.create(saleRequest(
                fixture,
                fixture.shiftId(),
                "1.000",
                UUID.randomUUID(),
                List.of(selection(fixture, fixture.lotId(), "1.000", List.of()))));
        UUID saleItemId = saleItemId(sale.id());
        CreateSaleReturnRequest request = new CreateSaleReturnRequest(
                "Devolucion de lote concurrente",
                List.of(new CreateSaleReturnRequest.Line(
                        saleItemId,
                        BigDecimal.ONE,
                        List.of(selection(
                                fixture, fixture.lotId(), "1.000", List.of())))));

        List<Outcome> outcomes = race(
                asActor(fixture.primaryActor(), () -> returns.create(sale.id(), request)),
                asActor(fixture.secondaryActor(), () -> returns.create(sale.id(), request)));

        assertThat(outcomes).filteredOn(Outcome::success).hasSize(1);
        assertThat(balance(fixture)).isEqualByComparingTo("1.000");
        assertThat(jdbc.queryForObject(
                        "SELECT quantity FROM inventory_lot_balances WHERE lot_id = ?",
                        BigDecimal.class,
                        fixture.lotId()))
                .isEqualByComparingTo("1.000");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM inventory_movements WHERE tenant_id = ? AND reference_type = 'POS_SALE_RETURN'",
                        Long.class,
                        fixture.tenantId()))
                .isOne();
    }

    private CreateSaleRequest saleRequest(
            Fixture fixture,
            UUID shiftId,
            String quantity,
            UUID confirmationId,
            List<InventoryTrackingSelectionRequest> selections) {
        BigDecimal amount = new BigDecimal(quantity).multiply(new BigDecimal("10.00"));
        return new CreateSaleRequest(
                fixture.branchId(),
                shiftId,
                null,
                BigDecimal.ZERO,
                List.of(new CreateSaleRequest.Item(
                        fixture.productId(),
                        new BigDecimal(quantity),
                        BigDecimal.ZERO,
                        selections)),
                List.of(new CreateSaleRequest.PaymentLine(
                        PaymentMethod.cash, amount, null)),
                confirmationId);
    }

    private static InventoryTrackingSelectionRequest selection(
            Fixture fixture,
            UUID lotId,
            String quantity,
            List<String> serialNumbers) {
        return new InventoryTrackingSelectionRequest(
                fixture.productId(),
                fixture.locationId(),
                lotId,
                new BigDecimal(quantity),
                serialNumbers);
    }

    private Fixture fixture(
            boolean trackingLot,
            boolean trackingSerial,
            BigDecimal quantity,
            List<String> serialNumbers) {
        UUID tenant = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        UUID role = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID secondaryUser = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        UUID shift = UUID.randomUUID();
        UUID secondaryShift = UUID.randomUUID();
        UUID lot = trackingLot ? UUID.randomUUID() : null;
        String suffix = tenant.toString().substring(0, 8);

        jdbc.update(
                "INSERT INTO tenants (id, name, slug, status, default_currency, timezone) VALUES (?, ?, ?, 'active', 'GTQ', 'America/Guatemala')",
                tenant, "POS trace " + suffix, "pos-trace-" + tenant);
        jdbc.update(
                "INSERT INTO branches (id, tenant_id, code, name, type, status) VALUES (?, ?, ?, 'Principal', 'store', 'active')",
                branch, tenant, "PT-" + suffix);
        jdbc.update(
                "INSERT INTO roles (id, tenant_id, name, permissions, branch_scope, status) VALUES (?, ?, ?, '{}', 'all', 'active')",
                role, tenant, "Rol " + suffix);
        jdbc.update(
                "INSERT INTO users (id, tenant_id, name, email, type, status, role_id, branch_id) VALUES (?, ?, 'Cajero', ?, 'employee', 'active', ?, ?)",
                user, tenant, user + "@pos-trace.test", role, branch);
        jdbc.update(
                "INSERT INTO users (id, tenant_id, name, email, type, status, role_id, branch_id) VALUES (?, ?, 'Cajero secundario', ?, 'employee', 'active', ?, ?)",
                secondaryUser, tenant, secondaryUser + "@pos-trace.test", role, branch);
        jdbc.update(
                "INSERT INTO categories (id, tenant_id, name, slug, status) VALUES (?, ?, 'Categoria', ?, 'active')",
                category, tenant, "cat-" + tenant);
        jdbc.update(
                "INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status) VALUES (?, ?, ?, 'Unidad', 'u', 'unit', true, 'active')",
                unit, tenant, "U-" + suffix);
        jdbc.update(
                "INSERT INTO products (id, tenant_id, sku, name, product_type, category_id, base_unit_id, sale_price, status, tracking_stock, tracking_lot, tracking_serial, channel_pos) VALUES (?, ?, ?, 'Trazable', 'physical', ?, ?, 10.00, 'published', true, ?, ?, true)",
                product, tenant, "SKU-" + suffix, category, unit, trackingLot, trackingSerial);
        jdbc.update(
                "INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status) VALUES (?, ?, ?, ?, 'Bodega', 'warehouse', 'active')",
                location, tenant, branch, "L-" + suffix);
        jdbc.update(
                "INSERT INTO inventory_balances (id, tenant_id, branch_id, product_id, location_id, quantity, reserved_quantity) VALUES (?, ?, ?, ?, ?, ?, 0)",
                UUID.randomUUID(), tenant, branch, product, location, quantity);
        jdbc.update(
                "INSERT INTO cash_shifts (id, tenant_id, branch_id, user_id, register_code, status, opened_at, opening_amount) VALUES (?, ?, ?, ?, ?, 'open', now(), 100.00)",
                shift, tenant, branch, user, "REG-" + suffix);
        jdbc.update(
                "INSERT INTO cash_shifts (id, tenant_id, branch_id, user_id, register_code, status, opened_at, opening_amount) VALUES (?, ?, ?, ?, ?, 'open', now(), 100.00)",
                secondaryShift, tenant, branch, secondaryUser, "REG2-" + suffix);
        if (lot != null) {
            jdbc.update(
                    "INSERT INTO inventory_lots (id, tenant_id, product_id, lot_number) VALUES (?, ?, ?, 'LOT-A')",
                    lot, tenant, product);
            jdbc.update(
                    "INSERT INTO inventory_lot_balances (id, tenant_id, branch_id, location_id, lot_id, quantity, reserved_quantity) VALUES (?, ?, ?, ?, ?, ?, 0)",
                    UUID.randomUUID(), tenant, branch, location, lot, quantity);
        }
        for (String serial : serialNumbers) {
            jdbc.update(
                    "INSERT INTO inventory_serials (id, tenant_id, branch_id, location_id, product_id, serial_number, lot_id, status, version) VALUES (?, ?, ?, ?, ?, ?, ?, 'AVAILABLE', 0)",
                    UUID.randomUUID(), tenant, branch, location, product, serial, lot);
        }

        AuthenticatedUser actor = new AuthenticatedUser(
                user, tenant, UserType.employee, role, branch, UUID.randomUUID());
        AuthenticatedUser secondaryActor = new AuthenticatedUser(
                secondaryUser, tenant, UserType.employee, role, branch, UUID.randomUUID());
        defaultActor = actor;
        return new Fixture(
                tenant, branch, user, shift, secondaryShift, product, location, lot,
                actor, secondaryActor);
    }

    private UUID addLot(Fixture fixture, String number, BigDecimal quantity) {
        UUID lot = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO inventory_lots (id, tenant_id, product_id, lot_number) VALUES (?, ?, ?, ?)",
                lot, fixture.tenantId(), fixture.productId(), number);
        jdbc.update(
                "INSERT INTO inventory_lot_balances (id, tenant_id, branch_id, location_id, lot_id, quantity, reserved_quantity) VALUES (?, ?, ?, ?, ?, ?, 0)",
                UUID.randomUUID(), fixture.tenantId(), fixture.branchId(), fixture.locationId(), lot, quantity);
        return lot;
    }

    private void configureSaleUnitConversion(Fixture fixture, String factor) {
        UUID baseUnitId = jdbc.queryForObject(
                "SELECT base_unit_id FROM products WHERE id = ?",
                UUID.class,
                fixture.productId());
        UUID saleUnitId = UUID.randomUUID();
        String suffix = saleUnitId.toString().substring(0, 8);
        jdbc.update(
                "INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status) VALUES (?, ?, ?, 'Caja', 'cj', 'unit', true, 'active')",
                saleUnitId,
                fixture.tenantId(),
                "BOX-" + suffix);
        jdbc.update(
                "UPDATE products SET sale_unit_id = ? WHERE id = ?",
                saleUnitId,
                fixture.productId());
        jdbc.update(
                "INSERT INTO unit_conversions (id, tenant_id, product_id, from_unit_id, to_unit_id, factor) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                fixture.tenantId(),
                fixture.productId(),
                saleUnitId,
                baseUnitId,
                new BigDecimal(factor));
    }

    private UUID addInventoryProduct(
            Fixture fixture, boolean trackingLot, boolean trackingSerial) {
        UUID product = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO products (id, tenant_id, sku, name, product_type, category_id, base_unit_id, sale_price, status, tracking_stock, tracking_lot, tracking_serial, channel_pos) SELECT ?, tenant_id, ?, 'Producto ajeno', 'physical', category_id, base_unit_id, 10.00, 'published', true, ?, ?, true FROM products WHERE id = ?",
                product,
                "FOREIGN-" + product,
                trackingLot,
                trackingSerial,
                fixture.productId());
        return product;
    }

    private UUID saleItemId(UUID saleId) {
        return jdbc.queryForObject(
                "SELECT id FROM sale_items WHERE sale_id = ?", UUID.class, saleId);
    }

    private BigDecimal balance(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT quantity FROM inventory_balances WHERE tenant_id = ? AND product_id = ? AND location_id = ?",
                BigDecimal.class,
                fixture.tenantId(),
                fixture.productId(),
                fixture.locationId());
    }

    private String serialStatus(Fixture fixture, String serial) {
        return jdbc.queryForObject(
                "SELECT status FROM inventory_serials WHERE tenant_id = ? AND product_id = ? AND serial_number = ?",
                String.class,
                fixture.tenantId(),
                fixture.productId(),
                serial);
    }

    private long movementCount(Fixture fixture, String referenceType, UUID referenceId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movements WHERE tenant_id = ? AND reference_type = ? AND reference_id = ?",
                Long.class,
                fixture.tenantId(),
                referenceType,
                referenceId);
    }

    private List<Outcome> race(Action firstAction, Action secondAction) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Outcome> first = executor.submit(() -> outcome(firstAction, ready, start));
            Future<Outcome> second = executor.submit(() -> outcome(secondAction, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private Action asActor(AuthenticatedUser actor, Action action) {
        return () -> {
            concurrentActor.set(actor);
            try {
                action.run();
            } finally {
                concurrentActor.remove();
            }
        };
    }

    private static Outcome outcome(Action action, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("La carrera no inicio a tiempo.");
        }
        try {
            action.run();
            return new Outcome(true, null);
        } catch (BusinessException exception) {
            return new Outcome(false, exception.getCode());
        }
    }

    @FunctionalInterface
    private interface Action {
        void run();
    }

    private record Outcome(boolean success, String code) {}

    private record Fixture(
            UUID tenantId,
            UUID branchId,
            UUID userId,
            UUID shiftId,
            UUID secondaryShiftId,
            UUID productId,
            UUID locationId,
            UUID lotId,
            AuthenticatedUser primaryActor,
            AuthenticatedUser secondaryActor) {}
}

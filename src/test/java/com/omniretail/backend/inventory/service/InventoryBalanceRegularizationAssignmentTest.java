package com.omniretail.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.ecommerce.entity.InventoryReservation;
import com.omniretail.backend.ecommerce.entity.InventoryReservationSourceType;
import com.omniretail.backend.inventory.dto.DeductStockCommand;
import com.omniretail.backend.inventory.dto.LegacyBalanceRegularizationPreviewResponse;
import com.omniretail.backend.inventory.dto.LegacyBalanceRegularizationPreviewResponse.Blocker;
import com.omniretail.backend.inventory.dto.LegacyBalanceRegularizationResultResponse;
import com.omniretail.backend.inventory.dto.RegularizationOptionsResponse;
import com.omniretail.backend.inventory.dto.RegularizeLegacyBalanceRequest;
import com.omniretail.backend.inventory.dto.ReserveInventoryCommand;
import com.omniretail.backend.inventory.dto.UpdateInventorySettingsRequest;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.PermissionResolver;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantEntitlementResolver;
import com.omniretail.backend.shared.security.TenantEntitlements;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Asignacion inicial de la ubicacion operativa dentro de la regularizacion ({@code assignDestination}):
 * productos heredados con {@code default_location_id = NULL} y existencias en el balance sin ubicacion.
 * Los productos se siembran con datos arbitrarios; ningun identificador real se codifica.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InventoryBalanceRegularizationAssignmentTest {

    private static final String ADJUSTMENT = "inventory.adjustment.create";
    private static final String PRODUCT_UPDATE = "catalog.products.update";
    private static final UUID ROLE_ID = UUID.randomUUID();
    private static final String REGULARIZATION_REFERENCE = "location_regularization";

    @Autowired private InventoryBalanceRegularizationService regularizationService;
    @Autowired private InventorySettingsService settingsService;
    @Autowired private InventoryStockService stockService;
    @Autowired private InventoryReservationLifecycleService lifecycleService;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;
    @MockitoBean private BranchAccessResolver branchAccessResolver;
    @MockitoBean private TenantEntitlementResolver entitlements;
    @MockitoBean private PermissionResolver permissionResolver;

    // ------------------------------------------------------------ caso principal

    @Test
    void unassignedDemoProductWithLegacyStockIsAssignedAndConsolidatedInOneOperation() {
        Fixture fixture = fixture(true, false, false);
        UUID shelf = location(fixture, "active");
        UUID nullBalance = balance(fixture, null, "100.000", "0.000");
        settingsRow(fixture, "7.000", null);

        // Antes: PUT /inventory/settings rechaza la asignacion (regresion de assertAssignmentChangeAllowed).
        assertCode(InventoryOperationalLocationService.LOCATION_CHANGE_BLOCKED_CODE,
                () -> settingsService.upsert(
                        fixture.branchId(), fixture.productId(),
                        new UpdateInventorySettingsRequest(new BigDecimal("7"), null, shelf)));
        assertThat(assignedInDb(fixture)).isNull();

        LegacyBalanceRegularizationResultResponse result = regularizationService.regularize(
                request(fixture, shelf, UUID.randomUUID(), true));

        UUID shelfBalance = balanceId(fixture, shelf);
        assertBalance(nullBalance, "0.000", "0.000");
        assertBalance(shelfBalance, "100.000", "0.000");
        assertThat(assignedInDb(fixture)).isEqualTo(shelf);
        // Los umbrales existentes se conservan: solo se asigna la ubicacion.
        assertThat(jdbc.queryForObject(
                        "SELECT min_stock FROM product_inventory_settings "
                                + "WHERE tenant_id = ? AND branch_id = ? AND product_id = ?",
                        BigDecimal.class, fixture.tenantId(), fixture.branchId(), fixture.productId()))
                .isEqualByComparingTo("7");
        assertThat(result.assignmentApplied()).isTrue();
        assertThat(result.previousAssignedLocationId()).isNull();
        assertThat(result.idempotent()).isFalse();
        assertThat(result.movedQuantity()).isEqualByComparingTo("100");
        // Un unico movimiento de auditoria y ningun in/out artificial.
        assertThat(count("SELECT COUNT(*) FROM inventory_movements WHERE tenant_id = ?", fixture.tenantId()))
                .isOne();
        assertThat(count(
                        "SELECT COUNT(*) FROM inventory_movements WHERE tenant_id = ? AND reference_type = ? "
                                + "AND type = 'transfer'",
                        fixture.tenantId(), REGULARIZATION_REFERENCE))
                .isOne();
        assertThat(count(
                        "SELECT COUNT(*) FROM inventory_balance_regularizations WHERE tenant_id = ?",
                        fixture.tenantId()))
                .isOne();
        // Ya asignado y consolidado, el producto vuelve a operar por ubicacion: la venta usa el destino.
        stockService.deductStock(new DeductStockCommand(
                fixture.tenantId(), fixture.branchId(), fixture.productId(), new BigDecimal("3.000"),
                "Venta", "POS_SALE", UUID.randomUUID(), UUID.randomUUID(), null));
        assertBalance(shelfBalance, "97.000", "0.000");
    }

    @Test
    void productWithoutAnySettingsRowIsAssignedWithDefaultThresholds() {
        Fixture fixture = fixture(true, false, false);
        UUID shelf = location(fixture, "active");
        balance(fixture, null, "15.000", "0.000");

        regularizationService.regularize(request(fixture, shelf, UUID.randomUUID(), true));

        assertThat(assignedInDb(fixture)).isEqualTo(shelf);
        assertThat(jdbc.queryForObject(
                        "SELECT min_stock FROM product_inventory_settings "
                                + "WHERE tenant_id = ? AND branch_id = ? AND product_id = ?",
                        BigDecimal.class, fixture.tenantId(), fixture.branchId(), fixture.productId()))
                .isEqualByComparingTo("0");
    }

    @Test
    void previewDescribesTheAssignmentInBothModes() {
        Fixture fixture = fixture(true, false, false);
        UUID shelf = location(fixture, "active");
        balance(fixture, null, "100.000", "0.000");
        login(fixture, true, true);

        LegacyBalanceRegularizationPreviewResponse normal =
                regularizationService.preview(fixture.branchId(), fixture.productId(), shelf);
        LegacyBalanceRegularizationPreviewResponse assign =
                regularizationService.preview(fixture.branchId(), fixture.productId(), shelf, true);

        assertThat(normal.assignedLocationId()).isNull();
        assertThat(normal.assignmentRequired()).isTrue();
        assertThat(normal.assignmentAllowed()).isTrue();
        assertThat(normal.eligible()).isFalse();
        assertThat(normal.blockers()).extracting(Blocker::code)
                .contains(InventoryBalanceRegularizationService.DESTINATION_NOT_ASSIGNED_CODE);
        assertThat(assign.eligible()).isTrue();
        assertThat(assign.blockers()).isEmpty();
        assertThat(assign.assignedLocationId()).isNull();
        assertThat(assign.assignmentRequired()).isTrue();
        assertThat(assign.assignmentAllowed()).isTrue();
        assertThat(assign.resultingQuantity()).isEqualByComparingTo("100");
        // La huella depende del modo: una vista previa no sirve para ejecutar en el otro.
        assertThat(assign.snapshotFingerprint()).isNotEqualTo(normal.snapshotFingerprint());
        // La vista previa nunca escribe.
        assertThat(assignedInDb(fixture)).isNull();
        assertThat(count("SELECT COUNT(*) FROM inventory_balance_regularizations WHERE tenant_id = ?",
                        fixture.tenantId()))
                .isZero();
    }

    // ------------------------------------------------------------ reservas

    @Test
    void activeReservationsAreReassignedDuringTheAssignment() {
        Fixture fixture = fixture(true, false, false);
        UUID nullBalance = balance(fixture, null, "100.000", "0.000");
        InventoryReservation reservation = lifecycleService.reserve(reserveCommand(fixture, "20.000"));
        UUID shelf = location(fixture, "active");
        assertBalance(nullBalance, "100.000", "20.000");

        LegacyBalanceRegularizationResultResponse result = regularizationService.regularize(
                request(fixture, shelf, UUID.randomUUID(), true));

        UUID shelfBalance = balanceId(fixture, shelf);
        assertBalance(nullBalance, "0.000", "0.000");
        assertBalance(shelfBalance, "100.000", "20.000");
        assertThat(result.assignmentApplied()).isTrue();
        assertThat(result.reservationsReassigned()).isOne();
        assertThat(jdbc.queryForObject(
                        "SELECT allocations -> 0 ->> 'balanceId' FROM inventory_reservations WHERE id = ?",
                        String.class, reservation.getId()))
                .isEqualTo(shelfBalance.toString());
        assertThat(jdbc.queryForObject(
                        "SELECT allocations -> 0 ->> 'locationId' FROM inventory_reservations WHERE id = ?",
                        String.class, reservation.getId()))
                .isEqualTo(shelf.toString());
        lifecycleService.consume(fixture.tenantId(), reservation.getId());
        assertBalance(shelfBalance, "80.000", "0.000");
        assertThat(nullBalanceQuantity(fixture)).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------ asignacion existente

    @Test
    void alreadyAssignedDestinationBehavesAsBeforeAndNeedsNoExtraPermission() {
        Fixture fixture = fixture(true, false, false);
        UUID shelf = location(fixture, "active");
        UUID nullBalance = balance(fixture, null, "40.000", "0.000");
        UUID shelfBalance = balance(fixture, shelf, "10.000", "0.000");
        settingsRow(fixture, "0.000", shelf);
        // Sin catalog.products.update: no hay asignacion nueva, asi que no se exige.
        RegularizeLegacyBalanceRequest request = request(fixture, shelf, UUID.randomUUID(), true, true, false);

        LegacyBalanceRegularizationResultResponse result = regularizationService.regularize(request);

        assertThat(result.assignmentApplied()).isFalse();
        assertThat(result.previousAssignedLocationId()).isEqualTo(shelf);
        assertBalance(nullBalance, "0.000", "0.000");
        assertBalance(shelfBalance, "50.000", "0.000");
        assertThat(assignedInDb(fixture)).isEqualTo(shelf);
    }

    @Test
    void anotherExistingAssignmentIsNeverChanged() {
        Fixture fixture = fixture(true, false, false);
        UUID shelfA = location(fixture, "active");
        UUID shelfB = location(fixture, "active");
        UUID nullBalance = balance(fixture, null, "5.000", "0.000");
        settingsRow(fixture, "0.000", shelfA);
        login(fixture, true, true);

        LegacyBalanceRegularizationPreviewResponse preview =
                regularizationService.preview(fixture.branchId(), fixture.productId(), shelfB, true);
        assertThat(preview.eligible()).isFalse();
        assertThat(preview.assignmentAllowed()).isFalse();
        assertThat(preview.assignedLocationId()).isEqualTo(shelfA);
        assertThat(preview.blockers()).extracting(Blocker::code)
                .contains(InventoryBalanceRegularizationService.ASSIGNMENT_CONFLICT_CODE);

        assertCode(InventoryBalanceRegularizationService.ASSIGNMENT_CONFLICT_CODE,
                () -> regularizationService.regularize(request(fixture, shelfB, UUID.randomUUID(), true)));

        assertThat(assignedInDb(fixture)).isEqualTo(shelfA);
        assertBalance(nullBalance, "5.000", "0.000");
        assertThat(regularizationRows(fixture)).isZero();
        assertThat(movementCount(fixture)).isZero();
    }

    @Test
    void withoutAssignModeAnUnassignedProductIsStillRejected() {
        Fixture fixture = fixture(true, false, false);
        UUID shelf = location(fixture, "active");
        UUID nullBalance = balance(fixture, null, "5.000", "0.000");

        assertCode(InventoryBalanceRegularizationService.DESTINATION_NOT_ASSIGNED_CODE,
                () -> regularizationService.regularize(request(fixture, shelf, UUID.randomUUID(), false)));

        assertThat(assignedInDb(fixture)).isNull();
        assertBalance(nullBalance, "5.000", "0.000");
    }

    @Test
    void assignmentIsRejectedWithLocationsDisabledOrAnInactiveDestination() {
        Fixture disabled = fixture(false, false, false);
        UUID disabledShelf = location(disabled, "active");
        balance(disabled, null, "5.000", "0.000");
        assertCode(InventoryBalanceRegularizationService.LOCATIONS_DISABLED_CODE,
                () -> regularizationService.regularize(
                        request(disabled, disabledShelf, UUID.randomUUID(), true)));
        assertThat(assignedInDb(disabled)).isNull();

        Fixture inactive = fixture(true, false, false);
        UUID inactiveShelf = location(inactive, "inactive");
        balance(inactive, null, "5.000", "0.000");
        assertCode(InventoryBalanceRegularizationService.DESTINATION_INACTIVE_CODE,
                () -> regularizationService.regularize(
                        request(inactive, inactiveShelf, UUID.randomUUID(), true)));
        assertThat(assignedInDb(inactive)).isNull();
    }

    // ------------------------------------------------------------ idempotencia y snapshot

    @Test
    void replayReturnsThePersistedAssignmentAndAnotherModeWithTheSameKeyIsRejected() {
        Fixture fixture = fixture(true, false, false);
        UUID shelf = location(fixture, "active");
        UUID nullBalance = balance(fixture, null, "100.000", "0.000");
        UUID key = UUID.randomUUID();
        RegularizeLegacyBalanceRequest request = request(fixture, shelf, key, true);

        LegacyBalanceRegularizationResultResponse first = regularizationService.regularize(request);
        LegacyBalanceRegularizationResultResponse replay = regularizationService.regularize(request);

        assertThat(first.idempotent()).isFalse();
        assertThat(replay.idempotent()).isTrue();
        assertThat(replay.regularizationId()).isEqualTo(first.regularizationId());
        assertThat(replay.movementId()).isEqualTo(first.movementId());
        assertThat(replay.assignmentApplied()).isTrue();
        assertThat(replay.previousAssignedLocationId()).isNull();
        // Misma clave en otro modo: otra solicitud.
        RegularizeLegacyBalanceRequest otherMode = request(fixture, shelf, key, false);
        assertCode(InventoryBalanceRegularizationService.IDEMPOTENCY_KEY_REUSED_CODE,
                () -> regularizationService.regularize(otherMode));
        // Una segunda solicitud con otra clave no duplica nada.
        assertCode(InventoryBalanceRegularizationService.NOT_REQUIRED_CODE,
                () -> regularizationService.regularize(request(fixture, shelf, UUID.randomUUID(), true)));

        assertBalance(nullBalance, "0.000", "0.000");
        assertBalance(balanceId(fixture, shelf), "100.000", "0.000");
        assertThat(regularizationRows(fixture)).isOne();
        assertThat(movementCount(fixture)).isOne();
    }

    @Test
    void aSnapshotBecomesStaleWhenTheAssignmentTheModeOrTheStockChange() {
        // Alguien asigna la ubicacion entre la vista previa y la ejecucion.
        Fixture assignedMeanwhile = fixture(true, false, false);
        UUID shelf = location(assignedMeanwhile, "active");
        UUID nullBalance = balance(assignedMeanwhile, null, "30.000", "0.000");
        RegularizeLegacyBalanceRequest request = request(assignedMeanwhile, shelf, UUID.randomUUID(), true);
        settingsRow(assignedMeanwhile, "0.000", shelf);
        assertCode(InventoryBalanceRegularizationService.STALE_SNAPSHOT_CODE,
                () -> regularizationService.regularize(request));
        assertBalance(nullBalance, "30.000", "0.000");

        // Vista previa en modo normal usada para ejecutar con asignacion.
        Fixture otherMode = fixture(true, false, false);
        UUID otherShelf = location(otherMode, "active");
        balance(otherMode, null, "30.000", "0.000");
        login(otherMode, true, true);
        LegacyBalanceRegularizationPreviewResponse normalPreview =
                regularizationService.preview(otherMode.branchId(), otherMode.productId(), otherShelf);
        RegularizeLegacyBalanceRequest crossed = new RegularizeLegacyBalanceRequest(
                otherMode.branchId(), otherMode.productId(), otherShelf, UUID.randomUUID(), "Motivo",
                normalPreview.sourceQuantity(), normalPreview.sourceReservedQuantity(),
                normalPreview.destinationQuantity(), normalPreview.snapshotFingerprint(), true);
        assertCode(InventoryBalanceRegularizationService.STALE_SNAPSHOT_CODE,
                () -> regularizationService.regularize(crossed));
        assertThat(assignedInDb(otherMode)).isNull();

        // El stock cambia.
        Fixture stock = fixture(true, false, false);
        UUID stockShelf = location(stock, "active");
        UUID stockNull = balance(stock, null, "30.000", "0.000");
        RegularizeLegacyBalanceRequest stale = request(stock, stockShelf, UUID.randomUUID(), true);
        jdbc.update("UPDATE inventory_balances SET quantity = 25 WHERE id = ?", stockNull);
        assertCode(InventoryBalanceRegularizationService.STALE_SNAPSHOT_CODE,
                () -> regularizationService.regularize(stale));
        assertThat(assignedInDb(stock)).isNull();
        assertBalance(stockNull, "25.000", "0.000");
    }

    // ------------------------------------------------------------ lotes, series y rollback

    @Test
    void lotsAndSerialsFollowTheAssignment() {
        Fixture lots = fixture(true, true, false);
        UUID lotsShelf = location(lots, "active");
        UUID lotA = lot(lots, "LOT-A");
        UUID lotB = lot(lots, "LOT-B");
        balance(lots, null, "10.000", "0.000");
        UUID nullLotA = lotBalance(lots, null, lotA, "6.000");
        UUID nullLotB = lotBalance(lots, null, lotB, "4.000");

        LegacyBalanceRegularizationResultResponse lotResult = regularizationService.regularize(
                request(lots, lotsShelf, UUID.randomUUID(), true));

        assertThat(lotResult.assignmentApplied()).isTrue();
        assertThat(lotResult.lotBalancesMerged()).isEqualTo(2);
        assertThat(lotQuantity(nullLotA)).isEqualByComparingTo("0");
        assertThat(lotQuantity(nullLotB)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject(
                        "SELECT COALESCE(SUM(quantity), 0) FROM inventory_lot_balances "
                                + "WHERE tenant_id = ? AND location_id = ?",
                        BigDecimal.class, lots.tenantId(), lotsShelf))
                .isEqualByComparingTo("10");
        assertThat(assignedInDb(lots)).isEqualTo(lotsShelf);

        Fixture serials = fixture(true, false, true);
        UUID serialsShelf = location(serials, "active");
        balance(serials, null, "2.000", "0.000");
        serial(serials, "SER-1");
        serial(serials, "SER-2");

        LegacyBalanceRegularizationResultResponse serialResult = regularizationService.regularize(
                request(serials, serialsShelf, UUID.randomUUID(), true));

        assertThat(serialResult.serialsRelocated()).isEqualTo(2);
        assertThat(count(
                        "SELECT COUNT(*) FROM inventory_serials WHERE tenant_id = ? AND location_id = ?",
                        serials.tenantId(), serialsShelf))
                .isEqualTo(2);
        assertThat(assignedInDb(serials)).isEqualTo(serialsShelf);
    }

    @Test
    void anyFailureRollsBackTheAssignmentTogetherWithTheConsolidation() {
        // Lotes que no suman el agregado: 409 y la asignacion no queda.
        Fixture lots = fixture(true, true, false);
        UUID lotsShelf = location(lots, "active");
        UUID lotsNull = balance(lots, null, "10.000", "0.000");
        lotBalance(lots, null, lot(lots, "LOT-A"), "6.000");
        assertCode(InventoryBalanceRegularizationService.TRACEABILITY_INCONSISTENT_CODE,
                () -> regularizationService.regularize(request(lots, lotsShelf, UUID.randomUUID(), true)));
        assertThat(assignedInDb(lots)).isNull();
        assertBalance(lotsNull, "10.000", "0.000");
        assertThat(count("SELECT COUNT(*) FROM inventory_balances WHERE tenant_id = ? AND location_id = ?",
                        lots.tenantId(), lotsShelf))
                .isZero();

        // Existencias en una tercera ubicacion: no se asigna nada.
        Fixture third = fixture(true, false, false);
        UUID thirdShelf = location(third, "active");
        UUID elsewhere = location(third, "active");
        UUID thirdNull = balance(third, null, "10.000", "0.000");
        balance(third, elsewhere, "4.000", "0.000");
        assertCode(InventoryBalanceRegularizationService.THIRD_LOCATION_STOCK_CODE,
                () -> regularizationService.regularize(request(third, thirdShelf, UUID.randomUUID(), true)));
        assertThat(assignedInDb(third)).isNull();
        assertBalance(thirdNull, "10.000", "0.000");

        // Reserva incoherente.
        Fixture drift = fixture(true, false, false);
        UUID driftShelf = location(drift, "active");
        balance(drift, null, "10.000", "5.000");
        assertCode(InventoryBalanceRegularizationService.RESERVATION_DRIFT_CODE,
                () -> regularizationService.regularize(request(drift, driftShelf, UUID.randomUUID(), true)));
        assertThat(assignedInDb(drift)).isNull();

        assertThat(regularizationRows(lots) + regularizationRows(third) + regularizationRows(drift)).isZero();
        assertThat(movementCount(lots) + movementCount(third) + movementCount(drift)).isZero();
    }

    // ------------------------------------------------------------ permisos, tenant y sucursal

    @Test
    void assignmentNeedsBothAdjustmentAndProductUpdatePermissions() {
        Fixture withoutUpdate = fixture(true, false, false);
        UUID shelf = location(withoutUpdate, "active");
        UUID nullBalance = balance(withoutUpdate, null, "5.000", "0.000");
        RegularizeLegacyBalanceRequest request =
                request(withoutUpdate, shelf, UUID.randomUUID(), true, true, false);

        LegacyBalanceRegularizationPreviewResponse preview =
                regularizationService.preview(withoutUpdate.branchId(), withoutUpdate.productId(), shelf, true);
        assertThat(preview.eligible()).isFalse();
        assertThat(preview.assignmentAllowed()).isFalse();
        assertThat(preview.blockers()).extracting(Blocker::code)
                .contains(InventoryBalanceRegularizationService.ASSIGNMENT_PERMISSION_CODE);
        assertCode("ACCESS_DENIED", () -> regularizationService.regularize(request));
        assertThat(assignedInDb(withoutUpdate)).isNull();
        assertBalance(nullBalance, "5.000", "0.000");

        Fixture withoutAdjustment = fixture(true, false, false);
        UUID otherShelf = location(withoutAdjustment, "active");
        balance(withoutAdjustment, null, "5.000", "0.000");
        RegularizeLegacyBalanceRequest second =
                request(withoutAdjustment, otherShelf, UUID.randomUUID(), true, false, true);
        assertCode("ACCESS_DENIED", () -> regularizationService.regularize(second));
        assertThat(assignedInDb(withoutAdjustment)).isNull();

        // Un usuario sin rol no puede asignar.
        Fixture noRole = fixture(true, false, false);
        UUID noRoleShelf = location(noRole, "active");
        balance(noRole, null, "5.000", "0.000");
        RegularizeLegacyBalanceRequest third = request(noRole, noRoleShelf, UUID.randomUUID(), true);
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                noRole.userId(), noRole.tenantId(), UserType.employee, null, noRole.branchId(),
                UUID.randomUUID()));
        assertCode("ACCESS_DENIED", () -> regularizationService.regularize(third));
        assertThat(assignedInDb(noRole)).isNull();
    }

    @Test
    void replayingAnAppliedAssignmentRequiresTheSamePermissions() {
        Fixture fixture = fixture(true, false, false);
        UUID shelf = location(fixture, "active");
        balance(fixture, null, "5.000", "0.000");
        RegularizeLegacyBalanceRequest request = request(fixture, shelf, UUID.randomUUID(), true);
        regularizationService.regularize(request);

        login(fixture, true, false);

        assertCode("ACCESS_DENIED", () -> regularizationService.regularize(request));
    }

    @Test
    void optionsExposeOnlyTheAssignedAndActiveLocationsOfTheBranch() {
        Fixture fixture = fixture(true, false, false);
        Fixture foreign = fixture(true, false, false);
        UUID shelfA = location(fixture, "active");
        UUID shelfB = location(fixture, "active");
        location(fixture, "inactive");
        UUID otherBranch = secondBranch(fixture);
        UUID otherBranchLocation = locationAt(fixture, otherBranch, "active");
        settingsRow(fixture, "0.000", shelfA);
        login(fixture, true, true);

        RegularizationOptionsResponse options =
                regularizationService.options(fixture.branchId(), fixture.productId());

        assertThat(options.locationsEnabled()).isTrue();
        assertThat(options.assignedLocationId()).isEqualTo(shelfA);
        assertThat(options.assignedLocation().id()).isEqualTo(shelfA);
        assertThat(options.assignableLocations()).extracting(RegularizationOptionsResponse.LocationOption::id)
                .containsExactlyInAnyOrder(shelfA, shelfB)
                .doesNotContain(otherBranchLocation);
        assertThat(options.productName()).isNotBlank();

        // Producto de otro tenant y sucursal fuera del alcance del usuario.
        assertCode("PRODUCT_NOT_FOUND",
                () -> regularizationService.options(fixture.branchId(), foreign.productId()));
        assertCode("BRANCH_NOT_FOUND",
                () -> regularizationService.options(foreign.branchId(), fixture.productId()));
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(false, Set.of()));
        assertCode("BRANCH_ACCESS_DENIED",
                () -> regularizationService.options(fixture.branchId(), fixture.productId()));
    }

    @Test
    void optionsReportDisabledLocationsAndAnUnassignedProduct() {
        Fixture disabled = fixture(false, false, false);
        location(disabled, "active");
        login(disabled, true, true);
        RegularizationOptionsResponse off =
                regularizationService.options(disabled.branchId(), disabled.productId());
        assertThat(off.locationsEnabled()).isFalse();
        assertThat(off.assignableLocations()).isEmpty();

        Fixture fixture = fixture(true, false, false);
        UUID shelf = location(fixture, "active");
        login(fixture, true, true);
        RegularizationOptionsResponse on = regularizationService.options(fixture.branchId(), fixture.productId());
        assertThat(on.assignedLocationId()).isNull();
        assertThat(on.assignedLocation()).isNull();
        assertThat(on.assignableLocations()).extracting(RegularizationOptionsResponse.LocationOption::id)
                .containsExactly(shelf);
    }

    @Test
    void foreignTenantAndBranchAreRejectedByPreviewAndExecutionWithAssignMode() {
        Fixture fixture = fixture(true, false, false);
        Fixture foreign = fixture(true, false, false);
        UUID shelf = location(fixture, "active");
        UUID foreignShelf = location(foreign, "active");
        UUID foreignNull = balance(foreign, null, "9.000", "0.000");
        balance(fixture, null, "5.000", "0.000");
        login(fixture, true, true);

        assertCode("PRODUCT_NOT_FOUND", () -> regularizationService.preview(
                fixture.branchId(), foreign.productId(), shelf, true));
        assertCode("LOCATION_NOT_FOUND", () -> regularizationService.preview(
                fixture.branchId(), fixture.productId(), foreignShelf, true));
        RegularizeLegacyBalanceRequest crossTenant = new RegularizeLegacyBalanceRequest(
                fixture.branchId(), foreign.productId(), shelf, UUID.randomUUID(), "Motivo",
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, "a".repeat(64), true);
        assertCode("PRODUCT_NOT_FOUND", () -> regularizationService.regularize(crossTenant));
        assertBalance(foreignNull, "9.000", "0.000");
        assertThat(assignedInDb(foreign)).isNull();
    }

    // ------------------------------------------------------------ errores de bloqueo

    @Test
    void lockWaitAndDeadlockFailuresAreRecognisedButIntegrityErrorsAreNot() {
        assertThat(InventoryBalanceRegularizationService.isLockWaitFailure(
                        new org.springframework.dao.CannotAcquireLockException("timeout")))
                .isTrue();
        assertThat(InventoryBalanceRegularizationService.isLockWaitFailure(
                        new org.springframework.dao.DeadlockLoserDataAccessException(
                                "deadlock", new RuntimeException())))
                .isTrue();
        assertThat(InventoryBalanceRegularizationService.isLockWaitFailure(
                        new RuntimeException("wrapped", new java.sql.SQLException("lock", "55P03"))))
                .isTrue();
        assertThat(InventoryBalanceRegularizationService.isLockWaitFailure(
                        new RuntimeException("wrapped", new java.sql.SQLException("deadlock", "40P01"))))
                .isTrue();
        assertThat(InventoryBalanceRegularizationService.isLockWaitFailure(
                        new org.springframework.dao.DataIntegrityViolationException(
                                "unique", new java.sql.SQLException("duplicate", "23505"))))
                .isFalse();
        assertThat(InventoryBalanceRegularizationService.isLockWaitFailure(
                        new IllegalStateException("otro error")))
                .isFalse();
    }

    // ------------------------------------------------------------ utilidades

    private RegularizeLegacyBalanceRequest request(
            Fixture fixture, UUID location, UUID key, boolean assign) {
        return request(fixture, location, key, assign, true, true);
    }

    private RegularizeLegacyBalanceRequest request(
            Fixture fixture, UUID location, UUID key, boolean assign, boolean adjustment, boolean update) {
        login(fixture, adjustment, update);
        LegacyBalanceRegularizationPreviewResponse preview =
                regularizationService.preview(fixture.branchId(), fixture.productId(), location, assign);
        return new RegularizeLegacyBalanceRequest(
                fixture.branchId(), fixture.productId(), location, key, "Asignacion inicial heredada",
                preview.sourceQuantity(), preview.sourceReservedQuantity(),
                preview.destinationQuantity(), preview.snapshotFingerprint(), assign);
    }

    private void login(Fixture fixture, boolean adjustment, boolean productUpdate) {
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                fixture.userId(), fixture.tenantId(), UserType.employee, ROLE_ID, fixture.branchId(),
                UUID.randomUUID()));
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(true, Set.of()));
        given(entitlements.resolve(any())).willReturn(
                new TenantEntitlements(true, true, EnumSet.allOf(SaasCapability.class)));
        given(permissionResolver.hasPermission(any(UUID.class), any(UUID.class), eq(ADJUSTMENT)))
                .willReturn(adjustment);
        given(permissionResolver.hasPermission(any(UUID.class), any(UUID.class), eq(PRODUCT_UPDATE)))
                .willReturn(productUpdate);
    }

    private static void assertCode(String code, ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(code));
    }

    private static ReserveInventoryCommand reserveCommand(Fixture fixture, String quantity) {
        return new ReserveInventoryCommand(
                fixture.tenantId(), fixture.branchId(), fixture.productId(),
                InventoryReservationSourceType.transfer, UUID.randomUUID(), UUID.randomUUID(),
                null, null, new BigDecimal(quantity));
    }

    private Fixture fixture(boolean locationsEnabled, boolean trackingLot, boolean trackingSerial) {
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO tenants (id, name, slug, status, default_currency, timezone) "
                        + "VALUES (?, ?, ?, 'active', 'GTQ', 'America/Guatemala')",
                tenantId, "Tenant " + suffix, "tenant-" + suffix);
        jdbc.update(
                "INSERT INTO business_capabilities_configs "
                        + "(id, tenant_id, preset, supports_multiple_locations) VALUES (?, ?, 'custom', ?)",
                UUID.randomUUID(), tenantId, locationsEnabled);
        jdbc.update(
                "INSERT INTO branches (id, tenant_id, code, name, type, status) "
                        + "VALUES (?, ?, ?, ?, 'main', 'active')",
                branchId, tenantId, "MAIN-" + suffix, "Principal " + suffix);
        jdbc.update(
                "INSERT INTO categories (id, tenant_id, name, slug, status) VALUES (?, ?, ?, ?, 'active')",
                categoryId, tenantId, "Categoria " + suffix, "categoria-" + suffix);
        jdbc.update(
                "INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status) "
                        + "VALUES (?, ?, ?, 'Unidad', 'und', 'unit', true, 'active')",
                unitId, tenantId, "U-" + suffix.substring(0, 8));
        jdbc.update(
                "INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id, product_type, "
                        + "tracking_stock, tracking_lot, tracking_serial) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'physical', true, ?, ?)",
                productId, tenantId, "SKU-" + suffix, "Producto " + suffix, categoryId, unitId,
                trackingLot, trackingSerial);
        jdbc.update(
                "INSERT INTO users (id, tenant_id, name, email, type, status, branch_id) "
                        + "VALUES (?, ?, 'Administrador', ?, 'employee', 'active', ?)",
                userId, tenantId, userId + "@test.local", branchId);
        Fixture fixture = new Fixture(tenantId, branchId, productId, userId);
        login(fixture, true, true);
        return fixture;
    }

    private UUID location(Fixture fixture, String status) {
        return locationAt(fixture, fixture.branchId(), status);
    }

    private UUID locationAt(Fixture fixture, UUID branchId, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status) "
                        + "VALUES (?, ?, ?, ?, 'Estante', 'warehouse', ?)",
                id, fixture.tenantId(), branchId, "LOC-" + id, status);
        return id;
    }

    private UUID secondBranch(Fixture fixture) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO branches (id, tenant_id, code, name, type, status) "
                        + "VALUES (?, ?, ?, 'Sucursal secundaria', 'store', 'active')",
                id, fixture.tenantId(), "BR-" + id);
        return id;
    }

    private void settingsRow(Fixture fixture, String minStock, UUID defaultLocation) {
        jdbc.update(
                "INSERT INTO product_inventory_settings "
                        + "(tenant_id, branch_id, product_id, min_stock, default_location_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                fixture.tenantId(), fixture.branchId(), fixture.productId(),
                new BigDecimal(minStock), defaultLocation);
    }

    private UUID assignedInDb(Fixture fixture) {
        List<UUID> rows = jdbc.query(
                "SELECT default_location_id FROM product_inventory_settings "
                        + "WHERE tenant_id = ? AND branch_id = ? AND product_id = ?",
                (resultSet, row) -> resultSet.getObject(1, UUID.class),
                fixture.tenantId(), fixture.branchId(), fixture.productId());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private UUID balance(Fixture fixture, UUID locationId, String quantity, String reserved) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO inventory_balances "
                        + "(id, tenant_id, branch_id, product_id, location_id, quantity, reserved_quantity) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, fixture.tenantId(), fixture.branchId(), fixture.productId(), locationId,
                new BigDecimal(quantity), new BigDecimal(reserved));
        return id;
    }

    private UUID balanceId(Fixture fixture, UUID locationId) {
        return jdbc.queryForObject(
                "SELECT id FROM inventory_balances WHERE tenant_id = ? AND branch_id = ? "
                        + "AND product_id = ? AND location_id = ?",
                UUID.class, fixture.tenantId(), fixture.branchId(), fixture.productId(), locationId);
    }

    private UUID lot(Fixture fixture, String number) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO inventory_lots (id, tenant_id, product_id, lot_number) VALUES (?, ?, ?, ?)",
                id, fixture.tenantId(), fixture.productId(), number);
        return id;
    }

    private UUID lotBalance(Fixture fixture, UUID locationId, UUID lotId, String quantity) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO inventory_lot_balances "
                        + "(id, tenant_id, branch_id, location_id, lot_id, quantity, reserved_quantity) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0)",
                id, fixture.tenantId(), fixture.branchId(), locationId, lotId, new BigDecimal(quantity));
        return id;
    }

    private void serial(Fixture fixture, String number) {
        jdbc.update(
                "INSERT INTO inventory_serials "
                        + "(id, tenant_id, branch_id, location_id, product_id, serial_number, status, version) "
                        + "VALUES (?, ?, ?, NULL, ?, ?, 'AVAILABLE', 0)",
                UUID.randomUUID(), fixture.tenantId(), fixture.branchId(), fixture.productId(), number);
    }

    private void assertBalance(UUID balanceId, String quantity, String reserved) {
        assertThat(jdbc.queryForObject(
                        "SELECT quantity FROM inventory_balances WHERE id = ?", BigDecimal.class, balanceId))
                .isEqualByComparingTo(quantity);
        assertThat(jdbc.queryForObject(
                        "SELECT reserved_quantity FROM inventory_balances WHERE id = ?",
                        BigDecimal.class, balanceId))
                .isEqualByComparingTo(reserved);
    }

    private BigDecimal lotQuantity(UUID lotBalanceId) {
        return jdbc.queryForObject(
                "SELECT quantity FROM inventory_lot_balances WHERE id = ?", BigDecimal.class, lotBalanceId);
    }

    private BigDecimal nullBalanceQuantity(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT quantity FROM inventory_balances WHERE tenant_id = ? AND branch_id = ? "
                        + "AND product_id = ? AND location_id IS NULL",
                BigDecimal.class, fixture.tenantId(), fixture.branchId(), fixture.productId());
    }

    private long count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Long.class, arguments);
    }

    private long movementCount(Fixture fixture) {
        return count("SELECT COUNT(*) FROM inventory_movements WHERE tenant_id = ?", fixture.tenantId());
    }

    private long regularizationRows(Fixture fixture) {
        return count(
                "SELECT COUNT(*) FROM inventory_balance_regularizations WHERE tenant_id = ?",
                fixture.tenantId());
    }

    private record Fixture(UUID tenantId, UUID branchId, UUID productId, UUID userId) {}
}

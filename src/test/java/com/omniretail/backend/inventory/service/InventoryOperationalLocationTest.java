package com.omniretail.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.dto.ProductTrackingDto;
import com.omniretail.backend.administration.dto.SaveBusinessConfigRequest;
import com.omniretail.backend.administration.entity.BusinessPreset;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BusinessConfigService;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.catalog.dto.LocationUpdateRequest;
import com.omniretail.backend.catalog.entity.LocationStatus;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.service.LocationService;
import com.omniretail.backend.ecommerce.entity.InventoryReservation;
import com.omniretail.backend.ecommerce.entity.InventoryReservationSourceType;
import com.omniretail.backend.ecommerce.repository.InventoryReservationRepository;
import com.omniretail.backend.inventory.dto.AddStockCommand;
import com.omniretail.backend.inventory.dto.DeductStockCommand;
import com.omniretail.backend.inventory.dto.InventoryInboundCommand;
import com.omniretail.backend.inventory.dto.ReserveInventoryCommand;
import com.omniretail.backend.inventory.dto.UpdateInventorySettingsRequest;
import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.inventory.service.InventoryOperationalLocationService.InboundIssue;
import com.omniretail.backend.inventory.service.InventoryOperationalLocationService.InboundMode;
import com.omniretail.backend.inventory.service.InventoryOperationalLocationService.InboundTarget;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantEntitlementResolver;
import com.omniretail.backend.shared.security.TenantEntitlements;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Ubicacion operativa unica por producto y sucursal (fase 1). */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InventoryOperationalLocationTest {

    @Autowired private InventoryStockService stockService;
    @Autowired private InventoryReservationLifecycleService lifecycleService;
    @Autowired private InventoryOperationalLocationService operationalLocations;
    @Autowired private InventoryTraceabilityMutationService traceabilityMutation;
    @Autowired private InventorySettingsService settingsService;
    @Autowired private LocationService locationService;
    @Autowired private BusinessConfigService businessConfigService;
    @Autowired private InventoryReservationRepository reservations;
    @Autowired private ProductRepository productRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private CurrentUser currentUser;
    @MockitoBean private BranchAccessResolver branchAccessResolver;
    @MockitoBean private TenantEntitlementResolver entitlements;

    // ---------------------------------------------------------------- reservas

    @Test
    void reserveUsesTheBalanceOfTheAssignedLocationAndRecordsItsLocation() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        UUID balanceId = createBalance(fixture, shelf, "40.000", "0.000");

        InventoryReservation reservation = lifecycleService.reserve(reserveCommand(fixture, "5.000"));

        assertBalance(balanceId, "40.000", "5.000");
        assertThat(reservation.getAllocations())
                .contains(balanceId.toString(), "\"locationId\":\"" + shelf + "\"");
        assertThat(countBalances(fixture)).isOne();
    }

    @Test
    void reserveKeepsUsingTheLegacyNullBalanceWhenTheProductIsNotAssigned() {
        Fixture fixture = createFixture(true);
        UUID balanceId = createBalance(fixture, null, "10.000", "0.000");

        InventoryReservation reservation = lifecycleService.reserve(reserveCommand(fixture, "2.000"));

        assertBalance(balanceId, "10.000", "2.000");
        assertThat(reservation.getAllocations()).contains("\"locationId\":null");
    }

    @Test
    void reserveKeepsUsingTheNullBalanceWhenLocationsAreDisabledEvenIfAssigned() {
        Fixture fixture = createFixture(false);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        UUID nullBalance = createBalance(fixture, null, "6.000", "0.000");
        createBalance(fixture, shelf, "40.000", "0.000");

        lifecycleService.reserve(reserveCommand(fixture, "2.000"));

        assertBalance(nullBalance, "6.000", "2.000");
    }

    @Test
    void reserveReportsTheLocationConflictInsteadOfAmbiguousInsufficientStock() {
        Fixture fixture = createFixture(true);
        UUID assigned = createLocation(fixture, fixture.branchId(), "active");
        UUID legacy = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, assigned);
        UUID assignedBalance = createBalance(fixture, assigned, "1.000", "0.000");
        UUID legacyBalance = createBalance(fixture, legacy, "268.000", "0.000");

        assertThatThrownBy(() -> lifecycleService.reserve(reserveCommand(fixture, "2.000")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(InventoryOperationalLocationService.LOCATION_CONFLICT_CODE);

        // Sin perdida de datos: ningun balance cambia.
        assertBalance(assignedBalance, "1.000", "0.000");
        assertBalance(legacyBalance, "268.000", "0.000");
    }

    @Test
    void reserveStillRejectsPlainInsufficientStockWithoutOtherBalances() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        createBalance(fixture, shelf, "1.000", "0.000");

        assertThatThrownBy(() -> lifecycleService.reserve(reserveCommand(fixture, "2.000")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo("INSUFFICIENT_STOCK");
    }

    @Test
    void reserveRejectsAnInactiveAssignedLocation() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "archived");
        assign(fixture, shelf);
        createBalance(fixture, shelf, "40.000", "0.000");

        assertThatThrownBy(() -> lifecycleService.reserve(reserveCommand(fixture, "2.000")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(InventoryOperationalLocationService.ASSIGNED_LOCATION_INACTIVE_CODE);
    }

    @Test
    void consumeAndReleaseWorkOnTheAssignedBalance() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        UUID balanceId = createBalance(fixture, shelf, "40.000", "0.000");

        InventoryReservation toRelease = lifecycleService.reserve(reserveCommand(fixture, "4.000"));
        lifecycleService.release(fixture.tenantId(), toRelease.getId());
        assertBalance(balanceId, "40.000", "0.000");

        InventoryReservation toConsume = lifecycleService.reserve(reserveCommand(fixture, "4.000"));
        lifecycleService.consume(fixture.tenantId(), toConsume.getId());
        assertBalance(balanceId, "36.000", "0.000");
    }

    @Test
    void historicReservationWithEmptyAllocationsStillConsumesTheNullBalance() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        UUID nullBalance = createBalance(fixture, null, "5.000", "2.000");
        UUID shelfBalance = createBalance(fixture, shelf, "50.000", "0.000");
        InventoryReservation historic = persistReservation(fixture, "2.000", "[]");

        lifecycleService.consume(fixture.tenantId(), historic.getId());

        assertBalance(nullBalance, "3.000", "0.000");
        assertBalance(shelfBalance, "50.000", "0.000");
    }

    // ---------------------------------------------------------------- entradas

    @Test
    void incrementStockGoesToTheAssignedLocation() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);

        InventoryMovement movement = stockService.incrementStock(
                addCommand(fixture, "7.000"));

        assertThat(movement.getToLocationId()).isEqualTo(shelf);
        assertThat(balanceQuantity(fixture, shelf)).isEqualByComparingTo("7.000");
        assertThat(countBalances(fixture)).isOne();
    }

    @Test
    void incrementStockFailsWithoutAnAssignedLocationWhenLocationsAreEnabled() {
        Fixture fixture = createFixture(true);

        assertThatThrownBy(() -> stockService.incrementStock(addCommand(fixture, "1.000")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(InventoryOperationalLocationService.LOCATION_NOT_ASSIGNED_CODE);
        assertThat(countBalances(fixture)).isZero();
    }

    @Test
    void incrementStockKeepsTheNullBalanceWhenLocationsAreDisabled() {
        Fixture fixture = createFixture(false);

        InventoryMovement movement = stockService.incrementStock(addCommand(fixture, "3.000"));

        assertThat(movement.getToLocationId()).isNull();
        assertThat(nullBalanceQuantity(fixture)).isEqualByComparingTo("3.000");
    }

    @Test
    void incrementStockRejectsAnInactiveAssignedLocation() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "archived");
        assign(fixture, shelf);

        assertThatThrownBy(() -> stockService.incrementStock(addCommand(fixture, "1.000")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(InventoryOperationalLocationService.ASSIGNED_LOCATION_INACTIVE_CODE);
    }

    @Test
    void incrementStockRejectsAnAssignedLocationOfAnotherBranch() {
        Fixture fixture = createFixture(true);
        UUID otherBranch = createBranch(fixture.tenantId());
        UUID foreignLocation = createLocation(fixture, otherBranch, "active");
        assign(fixture, foreignLocation);

        assertThatThrownBy(() -> stockService.incrementStock(addCommand(fixture, "1.000")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(InventoryOperationalLocationService.ASSIGNED_LOCATION_INVALID_CODE);
    }

    @Test
    void incrementStockRejectsAnotherTenantsProduct() {
        Fixture fixture = createFixture(true);
        Fixture other = createFixture(true);

        assertThatThrownBy(() -> stockService.incrementStock(new AddStockCommand(
                fixture.tenantId(), fixture.branchId(), other.productId(), new BigDecimal("1.000"),
                "Entrada", "MANUAL_ADJUSTMENT", UUID.randomUUID(), null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo("PRODUCT_NOT_FOUND");
    }

    @Test
    void incrementStockAtAnotherLocationRespondsConflict() {
        Fixture fixture = createFixture(true);
        UUID assigned = createLocation(fixture, fixture.branchId(), "active");
        UUID other = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, assigned);

        assertThatThrownBy(() -> stockService.incrementStockAtLocation(
                addCommand(fixture, "1.000"), other))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(InventoryOperationalLocationService.LOCATION_MISMATCH_CODE);
        assertThat(countBalances(fixture)).isZero();

        InventoryMovement accepted = stockService.incrementStockAtLocation(
                addCommand(fixture, "2.000"), assigned);
        assertThat(accepted.getToLocationId()).isEqualTo(assigned);
        assertThat(balanceQuantity(fixture, assigned)).isEqualByComparingTo("2.000");
    }

    @Test
    void incrementStockIsRejectedWhenAnotherBalanceStillHoldsStock() {
        Fixture fixture = createFixture(true);
        UUID assigned = createLocation(fixture, fixture.branchId(), "active");
        UUID legacy = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, assigned);
        UUID legacyBalance = createBalance(fixture, legacy, "700.000", "0.000");

        assertThatThrownBy(() -> stockService.incrementStock(addCommand(fixture, "1.000")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(InventoryOperationalLocationService.LOCATION_CONFLICT_CODE);
        assertBalance(legacyBalance, "700.000", "0.000");
        assertThat(countBalances(fixture)).isOne();
    }

    @Test
    void deductStockUsesTheAssignedBalanceAndRecordsItsLocation() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        UUID balanceId = createBalance(fixture, shelf, "10.000", "0.000");

        InventoryMovement movement = stockService.deductStock(new DeductStockCommand(
                fixture.tenantId(), fixture.branchId(), fixture.productId(), new BigDecimal("4.000"),
                "Venta", "POS_SALE", UUID.randomUUID(), null, null));

        assertThat(movement.getFromLocationId()).isEqualTo(shelf);
        assertBalance(balanceId, "6.000", "0.000");
    }

    @Test
    void traceabilityReceivePreloadsTheAssignedLocationAndRejectsAnotherOne() {
        Fixture fixture = createFixture(true);
        UUID assigned = createLocation(fixture, fixture.branchId(), "active");
        UUID other = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, assigned);
        Product product = productRepository
                .findByTenantIdAndId(fixture.tenantId(), fixture.productId())
                .orElseThrow();

        InventoryMovement preloaded = traceabilityMutation.receive(inbound(fixture, product, null));
        assertThat(preloaded.getToLocationId()).isEqualTo(assigned);
        assertThat(balanceQuantity(fixture, assigned)).isEqualByComparingTo("3.000");

        assertThatThrownBy(() -> traceabilityMutation.receive(inbound(fixture, product, other)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(InventoryOperationalLocationService.LOCATION_MISMATCH_CODE);
        assertThat(countBalances(fixture)).isOne();
    }

    @Test
    void traceabilityReceiveRefreshesTheBalanceAfterWaitingForItsLock() throws Exception {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        UUID balanceId = createBalance(fixture, shelf, "10.000", "2.000");
        Product product = productRepository
                .findByTenantIdAndId(fixture.tenantId(), fixture.productId())
                .orElseThrow();
        UUID inboundReferenceId = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch deductionLocked = new CountDownLatch(1);
        CountDownLatch releaseDeduction = new CountDownLatch(1);
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        try {
            Future<?> deduction = executor.submit(() -> template.executeWithoutResult(status -> {
                stockService.deductStock(
                        fixture.tenantId(), fixture.branchId(), fixture.productId(), new BigDecimal("3.000"));
                deductionLocked.countDown();
                awaitLatch(releaseDeduction);
            }));
            assertThat(deductionLocked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<InventoryMovement> inbound = executor.submit(() -> traceabilityMutation.receive(
                    new InventoryInboundCommand(
                            fixture.tenantId(),
                            fixture.branchId(),
                            product,
                            null,
                            new BigDecimal("5.000"),
                            List.of(),
                            "Entrada concurrente",
                            "GOODS_RECEIPT",
                            inboundReferenceId,
                            null,
                            null)));

            awaitBlockedSessions(1);
            assertThat(inbound.isDone()).isFalse();
            releaseDeduction.countDown();

            deduction.get(20, TimeUnit.SECONDS);
            InventoryMovement movement = inbound.get(20, TimeUnit.SECONDS);

            assertBalance(balanceId, "12.000", "2.000");
            assertThat(movement.getQuantityBefore()).isEqualByComparingTo("7.000");
            assertThat(movement.getQuantityAfter()).isEqualByComparingTo("12.000");
            assertThat(movement.getToLocationId()).isEqualTo(shelf);
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM inventory_movements "
                                    + "WHERE tenant_id = ? AND branch_id = ? AND product_id = ?",
                            Long.class,
                            fixture.tenantId(),
                            fixture.branchId(),
                            fixture.productId()))
                    .isEqualTo(2L);
        } finally {
            releaseDeduction.countDown();
            executor.shutdownNow();
        }
    }

    // -------------------------------------------------------- cambio de ubicacion

    @Test
    void assignmentChangeIsBlockedWhileStockSitsInAnotherLocation() {
        Fixture fixture = createFixture(true);
        UUID current = createLocation(fixture, fixture.branchId(), "active");
        UUID next = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, current);
        createBalance(fixture, current, "40.000", "0.000");

        assertThatThrownBy(() -> operationalLocations.assertAssignmentChangeAllowed(
                fixture.tenantId(), fixture.branchId(), fixture.productId(), next))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(InventoryOperationalLocationService.LOCATION_CHANGE_BLOCKED_CODE);
    }

    @Test
    void assignmentChangeIsBlockedWithPendingReservationsOutsideTheNewLocation() {
        Fixture fixture = createFixture(true);
        UUID next = createLocation(fixture, fixture.branchId(), "active");
        createBalance(fixture, null, "5.000", "2.000");

        assertThatThrownBy(() -> operationalLocations.assertAssignmentChangeAllowed(
                fixture.tenantId(), fixture.branchId(), fixture.productId(), next))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(InventoryOperationalLocationService.LOCATION_CHANGE_BLOCKED_CODE);
    }

    @Test
    void assignmentChangeIsBlockedWhenLotBalancesRemainElsewhere() {
        Fixture fixture = createFixture(true);
        UUID lotLocation = createLocation(fixture, fixture.branchId(), "active");
        UUID next = createLocation(fixture, fixture.branchId(), "active");
        UUID lotId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO inventory_lots (id, tenant_id, product_id, lot_number) VALUES (?, ?, ?, ?)",
                lotId, fixture.tenantId(), fixture.productId(), "L-" + lotId.toString().substring(0, 8));
        jdbcTemplate.update(
                "INSERT INTO inventory_lot_balances "
                        + "(id, tenant_id, branch_id, location_id, lot_id, quantity, reserved_quantity) "
                        + "VALUES (?, ?, ?, ?, ?, 5, 0)",
                UUID.randomUUID(), fixture.tenantId(), fixture.branchId(), lotLocation, lotId);

        assertThatThrownBy(() -> operationalLocations.assertAssignmentChangeAllowed(
                fixture.tenantId(), fixture.branchId(), fixture.productId(), next))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(InventoryOperationalLocationService.LOCATION_CHANGE_BLOCKED_CODE);
    }

    @Test
    void assigningTheLocationThatAlreadyHoldsAllTheStockIsAllowed() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        createBalance(fixture, shelf, "40.000", "0.000");

        operationalLocations.assertAssignmentChangeAllowed(
                fixture.tenantId(), fixture.branchId(), fixture.productId(), shelf);
    }

    @Test
    void assignmentChangeIsAllowedWithoutStockAndWhenLocationsAreDisabled() {
        Fixture enabled = createFixture(true);
        UUID next = createLocation(enabled, enabled.branchId(), "active");
        operationalLocations.assertAssignmentChangeAllowed(
                enabled.tenantId(), enabled.branchId(), enabled.productId(), next);

        Fixture disabled = createFixture(false);
        UUID loc = createLocation(disabled, disabled.branchId(), "active");
        createBalance(disabled, null, "9.000", "0.000");
        operationalLocations.assertAssignmentChangeAllowed(
                disabled.tenantId(), disabled.branchId(), disabled.productId(), loc);
    }

    // -------------------------------------------------------------- disponibilidad

    @Test
    void availabilityEqualsTheReservableBalance() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        UUID legacy = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        createBalance(fixture, shelf, "40.000", "6.000");
        createBalance(fixture, legacy, "100.000", "0.000");

        BigDecimal available = operationalLocations.availableQuantity(
                fixture.tenantId(), fixture.branchId(), fixture.productId());
        Map<UUID, BigDecimal> bulk = operationalLocations.availableByProduct(
                fixture.tenantId(), fixture.branchId());

        assertThat(available).isEqualByComparingTo("34.000");
        assertThat(bulk.get(fixture.productId())).isEqualByComparingTo("34.000");
        // Lo reservable coincide: 34 se reserva, 35 no.
        lifecycleService.reserve(reserveCommand(fixture, "34.000"));
        assertThat(operationalLocations.availableQuantity(
                        fixture.tenantId(), fixture.branchId(), fixture.productId()))
                .isEqualByComparingTo("0.000");
    }

    @Test
    void availabilityUsesTheNullBalanceWhenDisabledOrUnassignedAndZeroForInactiveAssignments() {
        Fixture disabled = createFixture(false);
        createBalance(disabled, null, "12.000", "2.000");
        assertThat(operationalLocations.availableQuantity(
                        disabled.tenantId(), disabled.branchId(), disabled.productId()))
                .isEqualByComparingTo("10.000");

        Fixture unassigned = createFixture(true);
        createBalance(unassigned, null, "8.000", "0.000");
        assertThat(operationalLocations.availableQuantity(
                        unassigned.tenantId(), unassigned.branchId(), unassigned.productId()))
                .isEqualByComparingTo("8.000");

        Fixture inactive = createFixture(true);
        UUID archived = createLocation(inactive, inactive.branchId(), "archived");
        assign(inactive, archived);
        createBalance(inactive, archived, "30.000", "0.000");
        assertThat(operationalLocations.availableQuantity(
                        inactive.tenantId(), inactive.branchId(), inactive.productId()))
                .isEqualByComparingTo("0.000");
        assertThat(operationalLocations.availableByProduct(inactive.tenantId(), inactive.branchId()))
                .doesNotContainKey(inactive.productId());
    }

    @Test
    void availabilityNeverCrossesTenantsOrBranches() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        createBalance(fixture, shelf, "40.000", "0.000");
        UUID otherBranch = createBranch(fixture.tenantId());
        Fixture otherTenant = createFixture(true);

        assertThat(operationalLocations.availableQuantity(
                        fixture.tenantId(), otherBranch, fixture.productId()))
                .isEqualByComparingTo("0.000");
        assertThat(operationalLocations.availableQuantity(
                        otherTenant.tenantId(), fixture.branchId(), fixture.productId()))
                .isEqualByComparingTo("0.000");
        assertThat(operationalLocations.availableByProduct(otherTenant.tenantId(), otherTenant.branchId()))
                .isEmpty();
    }

    // ----------------------------------------------------------------- concurrencia

    @Test
    void assignmentChangeAndAnIncomingStockEntryCannotInterleave() throws Exception {
        Fixture fixture = createFixture(true);
        UUID before = createLocation(fixture, fixture.branchId(), "active");
        UUID after = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, before);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseChange = new CountDownLatch(1);
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        try {
            Future<?> change = executor.submit(() -> template.executeWithoutResult(status -> {
                operationalLocations.assertAssignmentChangeAllowed(
                        fixture.tenantId(), fixture.branchId(), fixture.productId(), after);
                lockHeld.countDown();
                awaitLatch(releaseChange);
                jdbcTemplateUpdateAssignment(fixture, after);
            }));
            assertThat(lockHeld.await(10, TimeUnit.SECONDS)).isTrue();
            Future<InventoryMovement> entry = executor.submit(
                    () -> stockService.incrementStock(addCommand(fixture, "5.000")));

            // Determinista: la entrada queda esperando el bloqueo del producto hasta que el cambio termina.
            awaitBlockedSessions(1);
            assertThat(entry.isDone()).isFalse();
            releaseChange.countDown();

            change.get(20, TimeUnit.SECONDS);
            InventoryMovement movement = entry.get(20, TimeUnit.SECONDS);

            // La entrada espero al cambio y cayo en la nueva ubicacion asignada, nunca en la anterior.
            assertThat(movement.getToLocationId()).isEqualTo(after);
            assertThat(balanceQuantity(fixture, after)).isEqualByComparingTo("5.000");
            assertThat(countBalances(fixture)).isOne();
        } finally {
            releaseChange.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void twoConcurrentReservationsNeverOversellTheAssignedBalance() throws Exception {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        UUID balanceId = createBalance(fixture, shelf, "5.000", "0.000");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<Boolean>> results = List.of(
                    executor.submit(() -> reserveWhenReady(fixture, ready, start)),
                    executor.submit(() -> reserveWhenReady(fixture, ready, start)));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            long succeeded = results.stream().filter(result -> get(result)).count();
            assertThat(succeeded).isEqualTo(1);
            assertBalance(balanceId, "5.000", "4.000");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void decisionsOnTheSameProductDoNotBlockEachOtherNorForeignKeyInsertsButAssignmentChangesWait()
            throws Exception {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        createBalance(fixture, shelf, "10.000", "0.000");
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch holdsDecision = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        try {
            Future<?> holder = executor.submit(() -> template.executeWithoutResult(status -> {
                operationalLocations.resolveForSale(
                        fixture.tenantId(), fixture.branchId(), fixture.productId());
                holdsDecision.countDown();
                awaitLatch(release);
            }));
            assertThat(holdsDecision.await(10, TimeUnit.SECONDS)).isTrue();

            // Otra decision sobre el mismo producto (p. ej. otra sucursal o venta) no espera a la primera.
            Future<InventoryOperationalLocationService.OperationalLocation> other = executor.submit(
                    () -> template.execute(status -> operationalLocations.resolveForSale(
                            fixture.tenantId(), fixture.branchId(), fixture.productId())));
            assertThat(other.get(10, TimeUnit.SECONDS).locationId()).isEqualTo(shelf);
            // Un INSERT con clave foranea al producto (KEY SHARE) tampoco espera: no hay ciclo posible con
            // flujos que ya tienen un balance bloqueado y luego insertan movimientos.
            executor.submit(() -> insertLot(fixture)).get(10, TimeUnit.SECONDS);

            // El cambio de ubicacion asignada (exclusivo) si espera a que terminen las decisiones en curso.
            Future<?> change = executor.submit(() -> template.executeWithoutResult(
                    status -> operationalLocations.assertAssignmentChangeAllowed(
                            fixture.tenantId(), fixture.branchId(), fixture.productId(), shelf)));
            awaitBlockedSessions(1);
            assertThat(change.isDone()).isFalse();
            release.countDown();

            holder.get(20, TimeUnit.SECONDS);
            change.get(20, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void reservationsOfTwoProductsInTheSameOrderWaitForEachOtherAndBothSucceed() throws Exception {
        Fixture first = createFixture(true);
        Fixture second = createProduct(first);
        UUID shelf = createLocation(first, first.branchId(), "active");
        assign(first, shelf);
        assign(second, shelf);
        UUID balanceFirst = createBalance(first, shelf, "10.000", "0.000");
        UUID balanceSecond = createBalance(second, shelf, "10.000", "0.000");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch holdsBoth = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        try {
            Future<?> holder = executor.submit(() -> template.executeWithoutResult(status -> {
                lifecycleService.reserve(reserveCommand(first, "2.000"));
                lifecycleService.reserve(reserveCommand(second, "2.000"));
                holdsBoth.countDown();
                awaitLatch(release);
            }));
            assertThat(holdsBoth.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> waiter = executor.submit(() -> template.executeWithoutResult(status -> {
                lifecycleService.reserve(reserveCommand(first, "2.000"));
                lifecycleService.reserve(reserveCommand(second, "2.000"));
            }));
            awaitBlockedSessions(1);
            assertThat(waiter.isDone()).isFalse();
            release.countDown();

            holder.get(20, TimeUnit.SECONDS);
            waiter.get(20, TimeUnit.SECONDS);
            assertBalance(balanceFirst, "10.000", "4.000");
            assertBalance(balanceSecond, "10.000", "4.000");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * Caracterizacion: dos transacciones que reservan dos productos en orden opuesto forman un ciclo de
     * bloqueos de balance. El ciclo ya existia antes de la politica de ubicacion (el bloqueo del producto es
     * compartido y no participa); PostgreSQL aborta una (40P01) y la otra se completa. Lo que se verifica es
     * que el aborto es limpio: nada queda a medias. Evitarlo exige que los llamadores con varias lineas
     * (checkout, traslados) reserven ordenados por producto.
     */
    @Test
    void reservationsOfTwoProductsInOppositeOrderAbortOneTransactionCleanly() throws Exception {
        Fixture first = createFixture(true);
        Fixture second = createProduct(first);
        UUID shelf = createLocation(first, first.branchId(), "active");
        assign(first, shelf);
        assign(second, shelf);
        UUID balanceFirst = createBalance(first, shelf, "10.000", "0.000");
        UUID balanceSecond = createBalance(second, shelf, "10.000", "0.000");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch bothHoldTheirFirstLock = new CountDownLatch(2);
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        try {
            List<Future<?>> attempts = List.of(
                    executor.submit(() -> reserveInOrder(template, first, second, bothHoldTheirFirstLock)),
                    executor.submit(() -> reserveInOrder(template, second, first, bothHoldTheirFirstLock)));
            List<Throwable> failures = new ArrayList<>();
            for (Future<?> attempt : attempts) {
                try {
                    attempt.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException exception) {
                    failures.add(exception.getCause());
                }
            }

            assertThat(failures).hasSize(1);
            assertThat(hasSqlState(failures.get(0), "40P01")).isTrue();
            // La ganadora reservo ambos productos; la abortada no dejo ninguna reserva.
            assertBalance(balanceFirst, "10.000", "2.000");
            assertBalance(balanceSecond, "10.000", "2.000");
        } finally {
            bothHoldTheirFirstLock.countDown();
            bothHoldTheirFirstLock.countDown();
            executor.shutdownNow();
        }
    }

    // ------------------------------------------- restauraciones (anulaciones y devoluciones)

    @Test
    void voidRestoresToTheAssignedBalanceThatTheSaleDeducted() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        UUID balanceId = createBalance(fixture, shelf, "10.000", "0.000");
        UUID saleId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        sell(fixture, "4.000", "POS_SALE", saleId, lineId);
        assertBalance(balanceId, "6.000", "0.000");

        InventoryMovement restored = stockService.incrementStock(
                restoreCommand(fixture, "4.000", "POS_SALE_VOID", saleId, lineId));

        assertThat(restored.getToLocationId()).isEqualTo(shelf);
        assertBalance(balanceId, "10.000", "0.000");
        assertThat(countBalances(fixture)).isOne();
    }

    @Test
    void voidRestoresToTheLegacyNullBalanceEvenIfTheProductWasAssignedAfterTheSale() {
        Fixture fixture = createFixture(true);
        UUID nullBalance = createBalance(fixture, null, "10.000", "0.000");
        UUID saleId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        sell(fixture, "10.000", "POS_SALE", saleId, lineId);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        // Sin saldo en NULL la asignacion es legitima.
        operationalLocations.assertAssignmentChangeAllowed(
                fixture.tenantId(), fixture.branchId(), fixture.productId(), shelf);
        assign(fixture, shelf);

        InventoryMovement restored = stockService.incrementStock(
                restoreCommand(fixture, "10.000", "POS_SALE_VOID", saleId, lineId));

        // Vuelve a NULL (de donde salio), no a la asignada: nada se elige por suposicion.
        assertThat(restored.getToLocationId()).isNull();
        assertBalance(nullBalance, "10.000", "0.000");
        assertThat(countBalances(fixture)).isOne();
        // La politica de entradas sigue vigente para entradas normales: el saldo NULL queda fuera.
        assertFails(
                () -> stockService.incrementStock(addCommand(fixture, "1.000")),
                InventoryOperationalLocationService.LOCATION_CONFLICT_CODE);
    }

    @Test
    void voidRestoresToTheOriginalLocationEvenIfItIsNoLongerActive() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        UUID balanceId = createBalance(fixture, shelf, "10.000", "0.000");
        UUID saleId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        sell(fixture, "3.000", "POS_SALE", saleId, lineId);
        jdbcTemplate.update("UPDATE locations SET status = 'archived' WHERE id = ?", shelf);

        InventoryMovement restored = stockService.incrementStock(
                restoreCommand(fixture, "3.000", "POS_SALE_VOID", saleId, lineId));

        assertThat(restored.getToLocationId()).isEqualTo(shelf);
        assertBalance(balanceId, "10.000", "0.000");
        // Una entrada normal sigue rechazandose por ubicacion inactiva.
        assertFails(
                () -> stockService.incrementStock(addCommand(fixture, "1.000")),
                InventoryOperationalLocationService.ASSIGNED_LOCATION_INACTIVE_CODE);
    }

    @Test
    void kitComponentVoidRestoresToTheOriginalBalance() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        UUID balanceId = createBalance(fixture, shelf, "10.000", "0.000");
        UUID saleId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        sell(fixture, "2.000", "POS_KIT_SALE", saleId, lineId);

        stockService.incrementStock(
                restoreCommand(fixture, "2.000", "POS_KIT_SALE_VOID", saleId, lineId));

        assertBalance(balanceId, "10.000", "0.000");
    }

    @Test
    void restorationIsRejectedWhenTheOriginalSaleMovementCannotBeFound() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        UUID balanceId = createBalance(fixture, shelf, "10.000", "0.000");

        assertFails(
                () -> stockService.incrementStock(restoreCommand(
                        fixture, "1.000", "POS_SALE_VOID", UUID.randomUUID(), UUID.randomUUID())),
                InventoryOperationalLocationService.RESTORE_ORIGIN_NOT_FOUND_CODE);
        assertBalance(balanceId, "10.000", "0.000");
    }

    @Test
    void restorationIsRejectedWhenTheSaleDeductedFromMoreThanOneBalance() {
        Fixture fixture = createFixture(true);
        UUID shelfA = createLocation(fixture, fixture.branchId(), "active");
        UUID shelfB = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelfA);
        UUID balanceA = createBalance(fixture, shelfA, "5.000", "0.000");
        UUID balanceB = createBalance(fixture, shelfB, "5.000", "0.000");
        UUID saleId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        sell(fixture, "1.000", "POS_SALE", saleId, lineId);
        jdbcTemplateUpdateAssignment(fixture, shelfB);
        sell(fixture, "1.000", "POS_SALE", saleId, lineId);

        assertFails(
                () -> stockService.incrementStock(
                        restoreCommand(fixture, "1.000", "POS_SALE_VOID", saleId, lineId)),
                InventoryOperationalLocationService.RESTORE_ORIGIN_AMBIGUOUS_CODE);
        assertBalance(balanceA, "4.000", "0.000");
        assertBalance(balanceB, "4.000", "0.000");
    }

    @Test
    void restorationIsRejectedWhenItExceedsWhatTheSaleDeducted() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        UUID balanceId = createBalance(fixture, shelf, "10.000", "0.000");
        UUID saleId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        sell(fixture, "2.000", "POS_SALE", saleId, lineId);

        assertFails(
                () -> stockService.incrementStock(
                        restoreCommand(fixture, "3.000", "POS_SALE_VOID", saleId, lineId)),
                InventoryOperationalLocationService.RESTORE_ORIGIN_INVALID_CODE);
        assertBalance(balanceId, "8.000", "0.000");
    }

    @Test
    void restorationNeverUsesTheSaleOfAnotherTenantOrBranch() {
        Fixture fixture = createFixture(true);
        Fixture foreign = createFixture(true);
        UUID shelf = createLocation(foreign, foreign.branchId(), "active");
        assign(foreign, shelf);
        createBalance(foreign, shelf, "10.000", "0.000");
        UUID saleId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        sell(foreign, "2.000", "POS_SALE", saleId, lineId);
        UUID localShelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, localShelf);

        assertFails(
                () -> stockService.incrementStock(
                        restoreCommand(fixture, "1.000", "POS_SALE_VOID", saleId, lineId)),
                InventoryOperationalLocationService.RESTORE_ORIGIN_NOT_FOUND_CODE);
    }

    @Test
    void returnRestoresToTheOriginalBalanceThroughRestoreSoldStock() {
        Fixture fixture = createFixture(true);
        UUID nullBalance = createBalance(fixture, null, "10.000", "0.000");
        UUID saleId = UUID.randomUUID();
        UUID saleItemId = UUID.randomUUID();
        sell(fixture, "5.000", "POS_SALE", saleId, saleItemId);
        // El producto quedo asignado con saldo todavia en NULL (estado heredado que ya existe en datos).
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        AddStockCommand returnCommand = restoreCommand(
                fixture, "2.000", "POS_SALE_RETURN", UUID.randomUUID(), UUID.randomUUID());

        // Hoy POS usa incrementStock para devolver: la politica de entradas lo rechaza (brecha de H1).
        assertFails(
                () -> stockService.incrementStock(returnCommand),
                InventoryOperationalLocationService.LOCATION_CONFLICT_CODE);
        // Con la venta original, el stock vuelve al balance del que salio.
        InventoryMovement restored = stockService.restoreSoldStock(returnCommand, saleId, saleItemId);

        assertThat(restored.getToLocationId()).isNull();
        assertBalance(nullBalance, "7.000", "0.000");
        assertThat(countBalances(fixture)).isOne();
    }

    @Test
    void restorationKeepsTheLegacyNullBalanceWhenLocationsAreDisabled() {
        Fixture fixture = createFixture(false);
        UUID nullBalance = createBalance(fixture, null, "10.000", "0.000");
        UUID saleId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        sell(fixture, "4.000", "POS_SALE", saleId, lineId);

        stockService.incrementStock(restoreCommand(fixture, "4.000", "POS_SALE_VOID", saleId, lineId));
        assertBalance(nullBalance, "10.000", "0.000");

        // Sin origen identificable y con el control deshabilitado nada cambia respecto de antes.
        stockService.incrementStock(restoreCommand(
                fixture, "1.000", "POS_SALE_VOID", UUID.randomUUID(), UUID.randomUUID()));
        assertBalance(nullBalance, "11.000", "0.000");
    }

    // ----------------------------------------- productos heredados con balance sin ubicacion

    @Test
    void legacyProductKeepsReceivingOnItsNullBalanceWithoutMovingAnything() {
        Fixture fixture = createFixture(true);
        UUID nullBalance = createBalance(fixture, null, "10.000", "0.000");

        InventoryMovement movement = stockService.incrementStock(addCommand(fixture, "5.000"));

        assertThat(movement.getToLocationId()).isNull();
        assertBalance(nullBalance, "15.000", "0.000");
        assertThat(countBalances(fixture)).isOne();
    }

    @Test
    void legacyProductRejectsAnEntryAtASpecificLocationUntilItIsAssigned() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        UUID nullBalance = createBalance(fixture, null, "10.000", "0.000");

        assertFails(
                () -> stockService.incrementStockAtLocation(addCommand(fixture, "1.000"), shelf),
                InventoryOperationalLocationService.LOCATION_MISMATCH_CODE);
        assertBalance(nullBalance, "10.000", "0.000");
        assertThat(countBalances(fixture)).isOne();
    }

    @Test
    void legacyProductWithStrayStockElsewhereReportsTheConflictOnEntries() {
        Fixture fixture = createFixture(true);
        UUID stray = createLocation(fixture, fixture.branchId(), "active");
        createBalance(fixture, null, "10.000", "0.000");
        UUID strayBalance = createBalance(fixture, stray, "3.000", "0.000");

        assertFails(
                () -> stockService.incrementStock(addCommand(fixture, "1.000")),
                InventoryOperationalLocationService.LOCATION_CONFLICT_CODE);
        assertBalance(strayBalance, "3.000", "0.000");
    }

    @Test
    void legacyProductCannotBeAssignedWhileItsNullBalanceHoldsStock() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        UUID nullBalance = createBalance(fixture, null, "10.000", "0.000");

        assertFails(
                () -> operationalLocations.assertAssignmentChangeAllowed(
                        fixture.tenantId(), fixture.branchId(), fixture.productId(), shelf),
                InventoryOperationalLocationService.LOCATION_CHANGE_BLOCKED_CODE);
        assertBalance(nullBalance, "10.000", "0.000");
    }

    @Test
    void legacyProductSoldOutCanBeAssignedAndThenReceivesAtTheAssignedLocation() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        UUID nullBalance = createBalance(fixture, null, "0.000", "0.000");
        operationalLocations.assertAssignmentChangeAllowed(
                fixture.tenantId(), fixture.branchId(), fixture.productId(), shelf);
        assign(fixture, shelf);

        InventoryMovement movement = stockService.incrementStock(addCommand(fixture, "4.000"));

        assertThat(movement.getToLocationId()).isEqualTo(shelf);
        assertThat(balanceQuantity(fixture, shelf)).isEqualByComparingTo("4.000");
        assertBalance(nullBalance, "0.000", "0.000");
    }

    // ---------------------------- recepciones y traslados: prevencion y recuperacion

    @Test
    void inboundIssuesReportWhatCouldNotBeReceivedWithoutChangingAnything() {
        Fixture assigned = createFixture(true);
        Fixture unassigned = createProduct(assigned);
        Fixture legacy = createProduct(assigned);
        UUID shelf = createLocation(assigned, assigned.branchId(), "active");
        UUID other = createLocation(assigned, assigned.branchId(), "active");
        assign(assigned, shelf);
        createBalance(legacy, null, "4.000", "0.000");
        Map<UUID, UUID> planned = new LinkedHashMap<>();
        planned.put(assigned.productId(), other);
        planned.put(unassigned.productId(), shelf);
        planned.put(legacy.productId(), null);

        List<InventoryOperationalLocationService.InboundIssue> issues =
                operationalLocations.inboundIssues(assigned.tenantId(), assigned.branchId(), planned);

        assertThat(issues)
                .extracting(
                        InventoryOperationalLocationService.InboundIssue::productId,
                        InventoryOperationalLocationService.InboundIssue::code)
                .containsExactlyInAnyOrder(
                        tuple(assigned.productId(), InventoryOperationalLocationService.LOCATION_MISMATCH_CODE),
                        tuple(unassigned.productId(), InventoryOperationalLocationService.LOCATION_NOT_ASSIGNED_CODE));
        assertThat(countBalances(assigned)).isZero();
        assertThat(countBalances(unassigned)).isZero();
        assertThat(countBalances(legacy)).isOne();
        assertThat(operationalLocations.inboundIssues(
                        assigned.tenantId(), assigned.branchId(), Map.of(assigned.productId(), shelf)))
                .isEmpty();
        Fixture disabled = createFixture(false);
        assertThat(operationalLocations.inboundIssues(
                        disabled.tenantId(), disabled.branchId(), Map.of(disabled.productId(), shelf)))
                .isEmpty();
    }

    @Test
    void receiptWithProductsAssignedToDifferentLocationsSucceedsOncePerAssignedLocation() {
        Fixture first = createFixture(true);
        Fixture second = createProduct(first);
        UUID shelfFirst = createLocation(first, first.branchId(), "active");
        UUID shelfSecond = createLocation(first, first.branchId(), "active");
        assign(first, shelfFirst);
        assign(second, shelfSecond);

        // Un unico destino para toda la recepcion no sirve a ambos productos: 409 sin efectos parciales.
        stockService.incrementStockAtLocation(addCommand(first, "1.000"), shelfFirst);
        assertFails(
                () -> stockService.incrementStockAtLocation(addCommand(second, "2.000"), shelfFirst),
                InventoryOperationalLocationService.LOCATION_MISMATCH_CODE);
        assertThat(countBalances(second)).isZero();

        // Recuperacion sin tocar Compras ni Logistica: recibir cada producto con su ubicacion asignada.
        stockService.incrementStockAtLocation(addCommand(second, "2.000"), shelfSecond);
        assertThat(balanceQuantity(first, shelfFirst)).isEqualByComparingTo("1.000");
        assertThat(balanceQuantity(second, shelfSecond)).isEqualByComparingTo("2.000");
    }

    @Test
    void assigningTheDestinationLocationRecoversAReceiptBlockedByAMissingAssignment() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");

        assertFails(
                () -> stockService.incrementStockAtLocation(addCommand(fixture, "3.000"), shelf),
                InventoryOperationalLocationService.LOCATION_NOT_ASSIGNED_CODE);
        assertThat(countBalances(fixture)).isZero();

        operationalLocations.assertAssignmentChangeAllowed(
                fixture.tenantId(), fixture.branchId(), fixture.productId(), shelf);
        assign(fixture, shelf);
        stockService.incrementStockAtLocation(addCommand(fixture, "3.000"), shelf);

        assertThat(balanceQuantity(fixture, shelf)).isEqualByComparingTo("3.000");
    }

    // ------------------------------------- configuracion de inventario (PUT, reemplazo completo)

    @Test
    void settingsUpdateWithTheSameLocationOnlyChangesTheThresholds() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        createBalance(fixture, shelf, "40.000", "0.000");
        loginAs(fixture);

        settingsService.upsert(
                fixture.branchId(),
                fixture.productId(),
                new UpdateInventorySettingsRequest(new BigDecimal("3"), new BigDecimal("5"), shelf));

        assertThat(assignedLocation(fixture)).isEqualTo(shelf);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT min_stock FROM product_inventory_settings "
                                + "WHERE tenant_id = ? AND branch_id = ? AND product_id = ?",
                        BigDecimal.class, fixture.tenantId(), fixture.branchId(), fixture.productId()))
                .isEqualByComparingTo("3");
    }

    @Test
    void settingsUpdateWithoutLocationKeepsTheFullReplaceMeaningOnAnEmptyAssignment() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        loginAs(fixture);

        settingsService.upsert(
                fixture.branchId(),
                fixture.productId(),
                new UpdateInventorySettingsRequest(new BigDecimal("3"), null, null));

        // El contrato no distingue "omitido" de null: sigue limpiando la ubicacion, como antes.
        assertThat(assignedLocation(fixture)).isNull();
    }

    @Test
    void settingsUpdateWithoutLocationIsBlockedWhileTheAssignedBalanceHoldsStock() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        createBalance(fixture, shelf, "40.000", "0.000");
        loginAs(fixture);

        assertFails(
                () -> settingsService.upsert(
                        fixture.branchId(),
                        fixture.productId(),
                        new UpdateInventorySettingsRequest(new BigDecimal("3"), null, null)),
                InventoryOperationalLocationService.LOCATION_CHANGE_BLOCKED_CODE);

        assertThat(assignedLocation(fixture)).isEqualTo(shelf);
    }

    // ------------------------------------------------ ubicaciones asignadas a productos

    @Test
    void aLocationAssignedToProductsCanBeNeitherDeactivatedNorArchived() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        loginAs(fixture);

        for (LocationStatus status : List.of(LocationStatus.inactive, LocationStatus.archived)) {
            assertFails(
                    () -> locationService.update(shelf, new LocationUpdateRequest("Estante", status)),
                    "LOCATION_ASSIGNED_TO_PRODUCTS");
        }
        assertFails(() -> locationService.archive(shelf), "LOCATION_ASSIGNED_TO_PRODUCTS");

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM locations WHERE id = ?", String.class, shelf))
                .isEqualTo("active");
    }

    @Test
    void aLocationWithoutAssignedProductsCanStillBeDeactivated() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        loginAs(fixture);

        locationService.update(shelf, new LocationUpdateRequest("Estante", LocationStatus.inactive));

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM locations WHERE id = ?", String.class, shelf))
                .isEqualTo("inactive");
    }

    @Test
    void partialThenTotalReturnsRestoreTheSameOriginalBalanceWithoutTouchingOthers() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        UUID bystander = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        UUID balanceId = createBalance(fixture, shelf, "10.000", "0.000");
        UUID bystanderBalance = createBalance(fixture, bystander, "0.000", "0.000");
        UUID saleId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        sell(fixture, "10.000", "POS_SALE", saleId, lineId);

        stockService.restoreSoldStock(
                restoreCommand(fixture, "4.000", "POS_SALE_RETURN", UUID.randomUUID(), UUID.randomUUID()),
                saleId, lineId);
        assertBalance(balanceId, "4.000", "0.000");
        stockService.restoreSoldStock(
                restoreCommand(fixture, "6.000", "POS_SALE_RETURN", UUID.randomUUID(), UUID.randomUUID()),
                saleId, lineId);

        assertBalance(balanceId, "10.000", "0.000");
        assertBalance(bystanderBalance, "0.000", "0.000");
    }

    @Test
    void kitComponentReturnRestoresToTheBalanceOfTheKitSale() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        UUID balanceId = createBalance(fixture, shelf, "10.000", "0.000");
        UUID saleId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        sell(fixture, "6.000", "POS_KIT_SALE", saleId, lineId);

        InventoryMovement restored = stockService.restoreSoldStock(
                restoreCommand(fixture, "3.000", "POS_KIT_SALE_RETURN", UUID.randomUUID(), UUID.randomUUID()),
                saleId, lineId);

        assertThat(restored.getToLocationId()).isEqualTo(shelf);
        assertBalance(balanceId, "7.000", "0.000");
    }

    @Test
    void returnIsRejectedForAnotherProductOrBranchThanTheSoldOne() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        UUID balanceId = createBalance(fixture, shelf, "10.000", "0.000");
        UUID saleId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        sell(fixture, "4.000", "POS_SALE", saleId, lineId);
        Fixture otherProduct = createProduct(fixture);
        UUID otherBranch = createBranch(fixture.tenantId());
        Fixture otherBranchFixture = new Fixture(fixture.tenantId(), otherBranch, fixture.productId());

        assertFails(
                () -> stockService.restoreSoldStock(
                        restoreCommand(otherProduct, "1.000", "POS_SALE_RETURN",
                                UUID.randomUUID(), UUID.randomUUID()),
                        saleId, lineId),
                InventoryOperationalLocationService.RESTORE_ORIGIN_NOT_FOUND_CODE);
        assertFails(
                () -> stockService.restoreSoldStock(
                        restoreCommand(otherBranchFixture, "1.000", "POS_SALE_RETURN",
                                UUID.randomUUID(), UUID.randomUUID()),
                        saleId, lineId),
                InventoryOperationalLocationService.RESTORE_ORIGIN_NOT_FOUND_CODE);
        assertBalance(balanceId, "6.000", "0.000");
        assertThat(countBalances(otherProduct)).isZero();
    }

    // ------------------------------------- ventas anteriores a la migracion 045 (sin linea)

    @Test
    void legacySaleWithASingleMovementRestoresToItsAssignedLocation() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        UUID balanceId = createBalance(fixture, shelf, "10.000", "0.000");
        UUID saleId = UUID.randomUUID();
        sell(fixture, "4.000", "POS_SALE", saleId, null);

        // POS pasa la linea vendida como referencia aunque el movimiento original no la tenga.
        InventoryMovement restored = stockService.incrementStock(
                restoreCommand(fixture, "4.000", "POS_SALE_VOID", saleId, UUID.randomUUID()));

        assertThat(restored.getToLocationId()).isEqualTo(shelf);
        assertBalance(balanceId, "10.000", "0.000");
        assertThat(countBalances(fixture)).isOne();
    }

    @Test
    void legacySaleThatLeftFromTheNullBalanceRestoresToTheNullBalance() {
        Fixture fixture = createFixture(true);
        UUID nullBalance = createBalance(fixture, null, "10.000", "0.000");
        UUID saleId = UUID.randomUUID();
        sell(fixture, "10.000", "POS_SALE", saleId, null);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);

        InventoryMovement restored = stockService.restoreSoldStock(
                restoreCommand(fixture, "3.000", "POS_SALE_RETURN", UUID.randomUUID(), UUID.randomUUID()),
                saleId,
                UUID.randomUUID());

        assertThat(restored.getToLocationId()).isNull();
        assertBalance(nullBalance, "3.000", "0.000");
        assertThat(countBalances(fixture)).isOne();
    }

    @Test
    void legacySaleWithSeveralMovementsFromTheSameLocationIsNotAmbiguous() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        UUID balanceId = createBalance(fixture, shelf, "10.000", "0.000");
        UUID saleId = UUID.randomUUID();
        sell(fixture, "2.000", "POS_SALE", saleId, null);
        sell(fixture, "3.000", "POS_SALE", saleId, null);

        stockService.incrementStock(
                restoreCommand(fixture, "5.000", "POS_SALE_VOID", saleId, UUID.randomUUID()));

        assertBalance(balanceId, "10.000", "0.000");
    }

    @Test
    void legacySaleDeductedFromMoreThanOneBalanceIsAmbiguous() {
        Fixture fixture = createFixture(true);
        UUID shelfA = createLocation(fixture, fixture.branchId(), "active");
        UUID shelfB = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelfA);
        UUID balanceA = createBalance(fixture, shelfA, "5.000", "0.000");
        UUID balanceB = createBalance(fixture, shelfB, "5.000", "0.000");
        UUID saleId = UUID.randomUUID();
        sell(fixture, "1.000", "POS_SALE", saleId, null);
        jdbcTemplateUpdateAssignment(fixture, shelfB);
        sell(fixture, "1.000", "POS_SALE", saleId, null);

        assertFails(
                () -> stockService.incrementStock(
                        restoreCommand(fixture, "1.000", "POS_SALE_VOID", saleId, UUID.randomUUID())),
                InventoryOperationalLocationService.RESTORE_ORIGIN_AMBIGUOUS_CODE);
        assertBalance(balanceA, "4.000", "0.000");
        assertBalance(balanceB, "4.000", "0.000");
    }

    @Test
    void legacySaleDeductedFromTheNullBalanceAndFromALocationIsAmbiguous() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        UUID nullBalance = createBalance(fixture, null, "5.000", "0.000");
        UUID shelfBalance = createBalance(fixture, shelf, "5.000", "0.000");
        UUID saleId = UUID.randomUUID();
        sell(fixture, "1.000", "POS_SALE", saleId, null);
        assign(fixture, shelf);
        sell(fixture, "1.000", "POS_SALE", saleId, null);

        assertFails(
                () -> stockService.incrementStock(
                        restoreCommand(fixture, "1.000", "POS_SALE_VOID", saleId, UUID.randomUUID())),
                InventoryOperationalLocationService.RESTORE_ORIGIN_AMBIGUOUS_CODE);
        assertBalance(nullBalance, "4.000", "0.000");
        assertBalance(shelfBalance, "4.000", "0.000");
    }

    @Test
    void saleWithoutAnyMovementDoesNotInventAnOriginEvenWhenAnotherSaleHasLegacyOnes() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        UUID balanceId = createBalance(fixture, shelf, "10.000", "0.000");
        sell(fixture, "4.000", "POS_SALE", UUID.randomUUID(), null);

        assertFails(
                () -> stockService.incrementStock(restoreCommand(
                        fixture, "1.000", "POS_SALE_VOID", UUID.randomUUID(), UUID.randomUUID())),
                InventoryOperationalLocationService.RESTORE_ORIGIN_NOT_FOUND_CODE);
        assertBalance(balanceId, "6.000", "0.000");
    }

    @Test
    void legacyRestorationNeverUsesMovementsOfAnotherTenantOrBranch() {
        Fixture fixture = createFixture(true);
        UUID localShelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, localShelf);
        UUID saleId = UUID.randomUUID();
        Fixture foreign = createFixture(true);
        UUID foreignShelf = createLocation(foreign, foreign.branchId(), "active");
        assign(foreign, foreignShelf);
        createBalance(foreign, foreignShelf, "10.000", "0.000");
        sell(foreign, "2.000", "POS_SALE", saleId, null);
        UUID otherBranch = createBranch(fixture.tenantId());
        Fixture elsewhere = new Fixture(fixture.tenantId(), otherBranch, fixture.productId());
        UUID otherBranchShelf = createLocation(elsewhere, otherBranch, "active");
        assign(elsewhere, otherBranchShelf);
        createBalance(elsewhere, otherBranchShelf, "10.000", "0.000");
        sell(elsewhere, "2.000", "POS_SALE", saleId, null);

        assertFails(
                () -> stockService.incrementStock(restoreCommand(
                        fixture, "1.000", "POS_SALE_VOID", saleId, UUID.randomUUID())),
                InventoryOperationalLocationService.RESTORE_ORIGIN_NOT_FOUND_CODE);
        assertThat(countBalances(fixture)).isZero();
    }

    @Test
    void legacyRestorationCannotExceedWhatTheSaleDeducted() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        UUID balanceId = createBalance(fixture, shelf, "10.000", "0.000");
        UUID saleId = UUID.randomUUID();
        sell(fixture, "2.000", "POS_SALE", saleId, null);

        assertFails(
                () -> stockService.incrementStock(restoreCommand(
                        fixture, "3.000", "POS_SALE_VOID", saleId, UUID.randomUUID())),
                InventoryOperationalLocationService.RESTORE_ORIGIN_INVALID_CODE);
        assertBalance(balanceId, "8.000", "0.000");
    }

    @Test
    void kitComponentMovementsWithoutALineAreNotTreatedAsLegacySales() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        UUID balanceId = createBalance(fixture, shelf, "10.000", "0.000");
        UUID saleId = UUID.randomUUID();
        sell(fixture, "2.000", "POS_KIT_SALE", saleId, null);

        assertFails(
                () -> stockService.incrementStock(restoreCommand(
                        fixture, "2.000", "POS_KIT_SALE_VOID", saleId, UUID.randomUUID())),
                InventoryOperationalLocationService.RESTORE_ORIGIN_NOT_FOUND_CODE);
        assertBalance(balanceId, "8.000", "0.000");
    }

    @Test
    void modernSaleKeepsResolvingByLineAndIgnoresLegacyMovementsOfTheSameSale() {
        Fixture fixture = createFixture(true);
        UUID shelfA = createLocation(fixture, fixture.branchId(), "active");
        UUID shelfB = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelfA);
        UUID balanceA = createBalance(fixture, shelfA, "10.000", "0.000");
        UUID balanceB = createBalance(fixture, shelfB, "10.000", "0.000");
        UUID saleId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        sell(fixture, "2.000", "POS_SALE", saleId, lineId);
        jdbcTemplateUpdateAssignment(fixture, shelfB);
        sell(fixture, "1.000", "POS_SALE", saleId, null);

        InventoryMovement restored = stockService.incrementStock(
                restoreCommand(fixture, "2.000", "POS_SALE_VOID", saleId, lineId));

        // La busqueda por linea es la primera opcion: vuelve a A, sin ambiguedad con el movimiento sin linea.
        assertThat(restored.getToLocationId()).isEqualTo(shelfA);
        assertBalance(balanceA, "10.000", "0.000");
        assertBalance(balanceB, "9.000", "0.000");
    }

    // ---------------------------------- validacion de entradas por linea (inboundIssues)

    @Test
    void inboundIssuesKeepEveryLineOfTheSameProductWithItsOwnLocation() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        UUID other = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        UUID firstLine = UUID.randomUUID();
        UUID secondLine = UUID.randomUUID();
        UUID thirdLine = UUID.randomUUID();

        List<InboundIssue> issues = operationalLocations.inboundIssues(
                fixture.tenantId(),
                fixture.branchId(),
                List.of(
                        new InboundTarget(firstLine, fixture.productId(), shelf),
                        new InboundTarget(secondLine, fixture.productId(), other),
                        new InboundTarget(thirdLine, fixture.productId(), other)),
                InboundMode.REQUIRED);

        assertThat(issues)
                .extracting(InboundIssue::lineRef, InboundIssue::productId, InboundIssue::code)
                .containsExactly(
                        tuple(secondLine, fixture.productId(),
                                InventoryOperationalLocationService.LOCATION_MISMATCH_CODE),
                        tuple(thirdLine, fixture.productId(),
                                InventoryOperationalLocationService.LOCATION_MISMATCH_CODE));
    }

    @Test
    void inboundIssuesAcceptTheAssignedLocationInEveryMode() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        createBalance(fixture, shelf, "5.000", "0.000");

        for (InboundMode mode : InboundMode.values()) {
            assertThat(operationalLocations.inboundIssues(
                            fixture.tenantId(),
                            fixture.branchId(),
                            List.of(new InboundTarget(fixture.productId(), shelf)),
                            mode))
                    .isEmpty();
        }
        // Sin ubicacion indicada solo REQUIRED lo rechaza: los otros modos usan la asignada.
        assertThat(operationalLocations.inboundIssues(
                        fixture.tenantId(),
                        fixture.branchId(),
                        List.of(new InboundTarget(fixture.productId(), null)),
                        InboundMode.OPTIONAL))
                .isEmpty();
        assertThat(operationalLocations.inboundIssues(
                        fixture.tenantId(),
                        fixture.branchId(),
                        List.of(new InboundTarget(fixture.productId(), null)),
                        InboundMode.ASSIGNED_REQUIRED))
                .isEmpty();
    }

    @Test
    void inboundIssuesRequireAnExplicitLocationOnlyWhenTheOperationDoes() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        UUID line = UUID.randomUUID();

        List<InboundIssue> issues = operationalLocations.inboundIssues(
                fixture.tenantId(),
                fixture.branchId(),
                List.of(new InboundTarget(line, fixture.productId(), null)),
                InboundMode.REQUIRED);

        assertThat(issues)
                .extracting(InboundIssue::lineRef, InboundIssue::code)
                .containsExactly(tuple(line, InventoryOperationalLocationService.LOCATION_REQUIRED_CODE));
    }

    @Test
    void inboundIssuesTreatALegacyNullBalanceProductPerMode() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        createBalance(fixture, null, "4.000", "0.000");

        assertThat(inboundCodes(fixture, null, InboundMode.OPTIONAL)).isEmpty();
        assertThat(inboundCodes(fixture, null, InboundMode.ASSIGNED_REQUIRED))
                .containsExactly(InventoryOperationalLocationService.LOCATION_NOT_ASSIGNED_CODE);
        assertThat(inboundCodes(fixture, null, InboundMode.REQUIRED))
                .containsExactly(InventoryOperationalLocationService.LOCATION_REQUIRED_CODE);
        // Con una ubicacion explicita no puede recibir mientras siga sin asignacion.
        for (InboundMode mode : InboundMode.values()) {
            assertThat(inboundCodes(fixture, shelf, mode))
                    .containsExactly(InventoryOperationalLocationService.LOCATION_MISMATCH_CODE);
        }
    }

    @Test
    void inboundIssuesReportAnInactiveOrForeignAssignedLocation() {
        Fixture inactive = createFixture(true);
        UUID archived = createLocation(inactive, inactive.branchId(), "archived");
        assign(inactive, archived);
        assertThat(inboundCodes(inactive, archived, InboundMode.REQUIRED))
                .containsExactly(InventoryOperationalLocationService.ASSIGNED_LOCATION_INACTIVE_CODE);

        Fixture foreign = createFixture(true);
        UUID otherBranch = createBranch(foreign.tenantId());
        UUID foreignLocation = createLocation(foreign, otherBranch, "active");
        assign(foreign, foreignLocation);
        assertThat(inboundCodes(foreign, foreignLocation, InboundMode.REQUIRED))
                .containsExactly(InventoryOperationalLocationService.ASSIGNED_LOCATION_INVALID_CODE);
    }

    @Test
    void inboundIssuesNeverCrossTenantsOrBranches() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        Fixture otherTenant = createFixture(true);
        UUID otherBranch = createBranch(fixture.tenantId());

        // Producto de otro tenant: no existe para este.
        assertThat(operationalLocations.inboundIssues(
                        fixture.tenantId(),
                        fixture.branchId(),
                        List.of(new InboundTarget(otherTenant.productId(), shelf)),
                        InboundMode.REQUIRED))
                .extracting(InboundIssue::code)
                .containsExactly("PRODUCT_NOT_FOUND");
        // Otra sucursal del mismo tenant: la asignacion es de la sucursal original.
        assertThat(operationalLocations.inboundIssues(
                        fixture.tenantId(),
                        otherBranch,
                        List.of(new InboundTarget(fixture.productId(), shelf)),
                        InboundMode.REQUIRED))
                .extracting(InboundIssue::code)
                .containsExactly(InventoryOperationalLocationService.LOCATION_NOT_ASSIGNED_CODE);
        // Producto inexistente.
        assertThat(operationalLocations.inboundIssues(
                        fixture.tenantId(),
                        fixture.branchId(),
                        List.of(new InboundTarget(UUID.randomUUID(), shelf)),
                        InboundMode.OPTIONAL))
                .extracting(InboundIssue::code)
                .containsExactly("PRODUCT_NOT_FOUND");
    }

    @Test
    void inboundIssuesReportAProductWithoutAnyConfiguration() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");

        for (InboundMode mode : InboundMode.values()) {
            assertThat(inboundCodes(fixture, mode == InboundMode.ASSIGNED_REQUIRED ? null : shelf, mode))
                    .containsExactly(InventoryOperationalLocationService.LOCATION_NOT_ASSIGNED_CODE);
        }
        assertThat(countBalances(fixture)).isZero();
    }

    @Test
    void inboundIssuesStayEmptyWhenLocationsAreDisabledOrThereIsNothingToCheck() {
        Fixture disabled = createFixture(false);
        UUID shelf = createLocation(disabled, disabled.branchId(), "active");
        for (InboundMode mode : InboundMode.values()) {
            assertThat(operationalLocations.inboundIssues(
                            disabled.tenantId(),
                            disabled.branchId(),
                            List.of(new InboundTarget(disabled.productId(), shelf),
                                    new InboundTarget(disabled.productId(), null)),
                            mode))
                    .isEmpty();
        }
        assertThat(operationalLocations.inboundIssues(
                        disabled.tenantId(), disabled.branchId(), List.of(), InboundMode.REQUIRED))
                .isEmpty();
        assertThat(operationalLocations.inboundIssues(
                        disabled.tenantId(), disabled.branchId(), (java.util.Collection<InboundTarget>) null, null))
                .isEmpty();
    }

    // ----------------------------------------- traslados: destino capaz de recibir el producto

    @Test
    void transferDestinationCheckAcceptsAnAssignedActiveLocationAndNeverWrites() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);

        operationalLocations.requireTransferDestinationReceivable(
                fixture.tenantId(), fixture.branchId(), List.of(fixture.productId()));

        assertThat(countBalances(fixture)).isZero();
    }

    @Test
    void transferDestinationCheckRejectsUnassignedInactiveLegacyAndConflictingProducts() {
        Fixture unassigned = createFixture(true);
        assertFails(
                () -> operationalLocations.requireTransferDestinationReceivable(
                        unassigned.tenantId(), unassigned.branchId(), List.of(unassigned.productId())),
                InventoryOperationalLocationService.TRANSFER_DESTINATION_INVALID_CODE);

        Fixture inactive = createFixture(true);
        assign(inactive, createLocation(inactive, inactive.branchId(), "archived"));
        assertFails(
                () -> operationalLocations.requireTransferDestinationReceivable(
                        inactive.tenantId(), inactive.branchId(), List.of(inactive.productId())),
                InventoryOperationalLocationService.TRANSFER_DESTINATION_INVALID_CODE);

        // Heredado con balance NULL: la recepcion exige una ubicacion, asi que no puede recibir.
        Fixture legacy = createFixture(true);
        createBalance(legacy, null, "4.000", "0.000");
        assertFails(
                () -> operationalLocations.requireTransferDestinationReceivable(
                        legacy.tenantId(), legacy.branchId(), List.of(legacy.productId())),
                InventoryOperationalLocationService.TRANSFER_DESTINATION_INVALID_CODE);

        Fixture conflicting = createFixture(true);
        assign(conflicting, createLocation(conflicting, conflicting.branchId(), "active"));
        createBalance(
                conflicting, createLocation(conflicting, conflicting.branchId(), "active"), "3.000", "0.000");
        assertFails(
                () -> operationalLocations.requireTransferDestinationReceivable(
                        conflicting.tenantId(), conflicting.branchId(), List.of(conflicting.productId())),
                InventoryOperationalLocationService.TRANSFER_DESTINATION_INVALID_CODE);
    }

    @Test
    void transferDestinationCheckUsesTheDestinationBranchAndIsNeutralWhenDisabled() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        UUID otherBranch = createBranch(fixture.tenantId());

        // La asignacion es de la sucursal original, no de la otra sucursal.
        assertFails(
                () -> operationalLocations.requireTransferDestinationReceivable(
                        fixture.tenantId(), otherBranch, List.of(fixture.productId())),
                InventoryOperationalLocationService.TRANSFER_DESTINATION_INVALID_CODE);

        Fixture disabled = createFixture(false);
        operationalLocations.requireTransferDestinationReceivable(
                disabled.tenantId(), disabled.branchId(), List.of(disabled.productId()));
        operationalLocations.requireTransferDestinationReceivable(
                fixture.tenantId(), otherBranch, List.of());
    }

    // ------------------------- configuracion: apagar supportsMultipleLocations (BusinessConfigService)

    @Test
    void disablingLocationsIsRejectedWhileAnAssignedLocationHoldsStock() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        UUID balanceId = createBalance(fixture, shelf, "40.000", "0.000");
        loginAs(fixture);

        assertFails(
                () -> businessConfigService.saveConfig(configRequest(false)),
                InventoryOperationalLocationService.LOCATIONS_IN_USE_CODE);

        // El 409 no cambia la configuracion ni mueve nada.
        assertThat(locationsFlag(fixture)).isTrue();
        assertBalance(balanceId, "40.000", "0.000");
        assertThat(assignedLocation(fixture)).isEqualTo(shelf);
    }

    @Test
    void disablingLocationsIsRejectedForLegacyStockInAnUnassignedLocation() {
        Fixture fixture = createFixture(true);
        UUID legacy = createLocation(fixture, fixture.branchId(), "active");
        UUID balanceId = createBalance(fixture, legacy, "7.000", "0.000");
        loginAs(fixture);

        assertFails(
                () -> businessConfigService.saveConfig(configRequest(false)),
                InventoryOperationalLocationService.LOCATIONS_IN_USE_CODE);

        assertThat(locationsFlag(fixture)).isTrue();
        assertBalance(balanceId, "7.000", "0.000");
    }

    @Test
    void disablingLocationsIsRejectedWhileAReservationHoldsABalanceInALocation() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        createBalance(fixture, shelf, "5.000", "0.000");
        lifecycleService.reserve(reserveCommand(fixture, "5.000"));
        loginAs(fixture);

        assertFails(
                () -> businessConfigService.saveConfig(configRequest(false)),
                InventoryOperationalLocationService.LOCATIONS_IN_USE_CODE);

        assertThat(locationsFlag(fixture)).isTrue();
    }

    @Test
    void disablingLocationsIsAllowedWhenNoLocationBalanceHoldsStockOrReservations() {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        createBalance(fixture, shelf, "0.000", "0.000");
        UUID nullBalance = createBalance(fixture, null, "12.000", "0.000");
        loginAs(fixture);

        businessConfigService.saveConfig(configRequest(false));

        assertThat(locationsFlag(fixture)).isFalse();
        // Nada se mueve: el balance sin ubicacion conserva sus existencias.
        assertBalance(nullBalance, "12.000", "0.000");
    }

    @Test
    void enablingLocationsAndSavingAnAlreadyDisabledConfigAreNotGuarded() {
        Fixture fixture = createFixture(false);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        createBalance(fixture, shelf, "9.000", "0.000");
        loginAs(fixture);

        // Ya apagada: guardar de nuevo con false conserva el comportamiento actual.
        businessConfigService.saveConfig(configRequest(false));
        assertThat(locationsFlag(fixture)).isFalse();

        businessConfigService.saveConfig(configRequest(true));
        assertThat(locationsFlag(fixture)).isTrue();
    }

    @Test
    void disablingLocationsOnlyLooksAtTheTenantThatSavesTheConfig() {
        Fixture withoutStock = createFixture(true);
        Fixture withStock = createFixture(true);
        UUID shelf = createLocation(withStock, withStock.branchId(), "active");
        assign(withStock, shelf);
        createBalance(withStock, shelf, "40.000", "0.000");
        loginAs(withoutStock);

        businessConfigService.saveConfig(configRequest(false));

        assertThat(locationsFlag(withoutStock)).isFalse();
        assertThat(locationsFlag(withStock)).isTrue();
    }

    @Test
    void anInventoryDecisionWaitsForAConfigurationChangeAndThenSeesTheNewState() throws Exception {
        Fixture fixture = createFixture(true);
        loginAs(fixture);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch configLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        try {
            Future<?> change = executor.submit(() -> template.executeWithoutResult(status -> {
                businessConfigService.saveConfig(configRequest(false));
                configLocked.countDown();
                awaitLatch(release);
            }));
            assertThat(configLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<InventoryOperationalLocationService.OperationalLocation> decision = executor.submit(
                    () -> template.execute(status -> operationalLocations.resolveForSale(
                            fixture.tenantId(), fixture.branchId(), fixture.productId())));

            awaitBlockedSessions(1);
            assertThat(decision.isDone()).isFalse();
            release.countDown();

            change.get(20, TimeUnit.SECONDS);
            assertThat(decision.get(20, TimeUnit.SECONDS).enabled()).isFalse();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void aConfigurationChangeWaitsForAnInventoryDecisionInProgressAndThenSeesItsStock() throws Exception {
        Fixture fixture = createFixture(true);
        UUID shelf = createLocation(fixture, fixture.branchId(), "active");
        assign(fixture, shelf);
        createBalance(fixture, shelf, "5.000", "0.000");
        loginAs(fixture);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch decided = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        try {
            Future<?> decision = executor.submit(() -> template.executeWithoutResult(status -> {
                lifecycleService.reserve(reserveCommand(fixture, "2.000"));
                decided.countDown();
                awaitLatch(release);
            }));
            assertThat(decided.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> change = executor.submit(() -> businessConfigService.saveConfig(configRequest(false)));

            awaitBlockedSessions(1);
            assertThat(change.isDone()).isFalse();
            release.countDown();

            decision.get(20, TimeUnit.SECONDS);
            // Al continuar ve la reserva ya confirmada y rechaza el apagado.
            assertThatThrownBy(() -> change.get(20, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception.getCause()).getCode())
                            .isEqualTo(InventoryOperationalLocationService.LOCATIONS_IN_USE_CODE));
            assertThat(locationsFlag(fixture)).isTrue();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    // --------------------------------------------------------------------- helpers

    private SaveBusinessConfigRequest configRequest(boolean multipleLocations) {
        return new SaveBusinessConfigRequest(
                BusinessPreset.custom,
                true,
                false,
                false,
                false,
                multipleLocations,
                true,
                false,
                false,
                false,
                List.of("cash"),
                new ProductTrackingDto(true, false, false, false));
    }

    private boolean locationsFlag(Fixture fixture) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT supports_multiple_locations FROM business_capabilities_configs WHERE tenant_id = ?",
                Boolean.class, fixture.tenantId()));
    }

    private List<String> inboundCodes(Fixture fixture, UUID locationId, InboundMode mode) {
        return operationalLocations
                .inboundIssues(
                        fixture.tenantId(),
                        fixture.branchId(),
                        List.of(new InboundTarget(fixture.productId(), locationId)),
                        mode)
                .stream()
                .map(InboundIssue::code)
                .toList();
    }

    private void reserveInOrder(
            TransactionTemplate template, Fixture one, Fixture two, CountDownLatch bothHoldTheirFirstLock) {
        template.executeWithoutResult(status -> {
            lifecycleService.reserve(reserveCommand(one, "2.000"));
            bothHoldTheirFirstLock.countDown();
            awaitLatch(bothHoldTheirFirstLock);
            lifecycleService.reserve(reserveCommand(two, "2.000"));
        });
    }

    private static boolean hasSqlState(Throwable failure, String sqlState) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && sqlState.equals(sql.getSQLState())) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private static void assertFails(ThrowingCallable callable, String code) {
        assertThatThrownBy(callable)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(code);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("La senal de la prueba no llego a tiempo.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    /** Espera, sin dormir por tiempo fijo, a que PostgreSQL reporte sesiones esperando un bloqueo. */
    private void awaitBlockedSessions(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer blocked = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_stat_activity "
                            + "WHERE datname = current_database() AND wait_event_type = 'Lock'",
                    Integer.class);
            if (blocked != null && blocked >= expected) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new IllegalStateException("Ninguna sesion quedo esperando un bloqueo.");
    }

    private void loginAs(Fixture fixture) {
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                UUID.randomUUID(), fixture.tenantId(), UserType.employee,
                UUID.randomUUID(), fixture.branchId(), UUID.randomUUID()));
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(true, Set.of()));
        given(entitlements.resolve(any())).willReturn(
                new TenantEntitlements(true, true, EnumSet.allOf(SaasCapability.class)));
    }

    private UUID assignedLocation(Fixture fixture) {
        return jdbcTemplate.queryForObject(
                "SELECT default_location_id FROM product_inventory_settings "
                        + "WHERE tenant_id = ? AND branch_id = ? AND product_id = ?",
                UUID.class, fixture.tenantId(), fixture.branchId(), fixture.productId());
    }

    private void insertLot(Fixture fixture) {
        UUID lotId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO inventory_lots (id, tenant_id, product_id, lot_number) VALUES (?, ?, ?, ?)",
                lotId, fixture.tenantId(), fixture.productId(), "L-" + lotId.toString().substring(0, 8));
    }

    private InventoryMovement sell(
            Fixture fixture, String quantity, String referenceType, UUID saleId, UUID lineId) {
        return stockService.deductStock(new DeductStockCommand(
                fixture.tenantId(), fixture.branchId(), fixture.productId(), new BigDecimal(quantity),
                "Venta", referenceType, saleId, lineId, null));
    }

    private static AddStockCommand restoreCommand(
            Fixture fixture, String quantity, String referenceType, UUID referenceId, UUID lineId) {
        return new AddStockCommand(
                fixture.tenantId(), fixture.branchId(), fixture.productId(), new BigDecimal(quantity),
                "Restauracion", referenceType, referenceId, lineId, null);
    }

    /** Otro producto fisico del mismo tenant y sucursal. */
    private Fixture createProduct(Fixture fixture) {
        UUID productId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id, "
                        + "product_type, tracking_stock) "
                        + "SELECT ?, tenant_id, ?, ?, category_id, base_unit_id, 'physical', true "
                        + "FROM products WHERE id = ?",
                productId, "SKU-" + productId, "Producto " + productId, fixture.productId());
        return new Fixture(fixture.tenantId(), fixture.branchId(), productId);
    }

    private boolean reserveWhenReady(Fixture fixture, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        start.await(10, TimeUnit.SECONDS);
        try {
            lifecycleService.reserve(reserveCommand(fixture, "4.000"));
            return true;
        } catch (BusinessException exception) {
            return false;
        }
    }

    private static boolean get(Future<Boolean> future) {
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void jdbcTemplateUpdateAssignment(Fixture fixture, UUID locationId) {
        jdbcTemplate.update(
                "UPDATE product_inventory_settings SET default_location_id = ? "
                        + "WHERE tenant_id = ? AND branch_id = ? AND product_id = ?",
                locationId, fixture.tenantId(), fixture.branchId(), fixture.productId());
    }

    private ReserveInventoryCommand reserveCommand(Fixture fixture, String quantity) {
        return new ReserveInventoryCommand(
                fixture.tenantId(),
                fixture.branchId(),
                fixture.productId(),
                InventoryReservationSourceType.transfer,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                new BigDecimal(quantity));
    }

    private static AddStockCommand addCommand(Fixture fixture, String quantity) {
        return new AddStockCommand(
                fixture.tenantId(),
                fixture.branchId(),
                fixture.productId(),
                new BigDecimal(quantity),
                "Entrada",
                "MANUAL_ADJUSTMENT",
                UUID.randomUUID(),
                null,
                null);
    }

    private static InventoryInboundCommand inbound(Fixture fixture, Product product, UUID locationId) {
        return new InventoryInboundCommand(
                fixture.tenantId(),
                fixture.branchId(),
                product,
                locationId,
                new BigDecimal("3.000"),
                List.of(),
                "Entrada",
                "MANUAL_ADJUSTMENT",
                UUID.randomUUID(),
                null,
                null);
    }

    private InventoryReservation persistReservation(Fixture fixture, String quantity, String allocations) {
        InventoryReservation reservation = InventoryReservation.builder()
                .branchId(fixture.branchId())
                .sourceType(InventoryReservationSourceType.transfer)
                .sourceId(UUID.randomUUID())
                .sourceLineId(UUID.randomUUID())
                .productId(fixture.productId())
                .quantity(new BigDecimal(quantity))
                .allocations(allocations)
                .build();
        reservation.setTenantId(fixture.tenantId());
        return reservations.saveAndFlush(reservation);
    }

    private void assign(Fixture fixture, UUID locationId) {
        jdbcTemplate.update(
                "INSERT INTO product_inventory_settings "
                        + "(tenant_id, branch_id, product_id, default_location_id) VALUES (?, ?, ?, ?)",
                fixture.tenantId(), fixture.branchId(), fixture.productId(), locationId);
    }

    private UUID createBalance(Fixture fixture, UUID locationId, String quantity, String reserved) {
        UUID balanceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO inventory_balances "
                        + "(id, tenant_id, branch_id, product_id, location_id, quantity, reserved_quantity) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                balanceId, fixture.tenantId(), fixture.branchId(), fixture.productId(), locationId,
                new BigDecimal(quantity), new BigDecimal(reserved));
        return balanceId;
    }

    private UUID createLocation(Fixture fixture, UUID branchId, String status) {
        UUID locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status) "
                        + "VALUES (?, ?, ?, ?, 'Estante', 'warehouse', ?)",
                locationId, fixture.tenantId(), branchId, "LOC-" + locationId, status);
        return locationId;
    }

    private UUID createBranch(UUID tenantId) {
        UUID branchId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO branches (id, tenant_id, code, name, type, status) "
                        + "VALUES (?, ?, ?, 'Sucursal secundaria', 'store', 'active')",
                branchId, tenantId, "BR-" + branchId);
        return branchId;
    }

    private void assertBalance(UUID balanceId, String quantity, String reserved) {
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT quantity FROM inventory_balances WHERE id = ?", BigDecimal.class, balanceId))
                .isEqualByComparingTo(quantity);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT reserved_quantity FROM inventory_balances WHERE id = ?",
                        BigDecimal.class, balanceId))
                .isEqualByComparingTo(reserved);
    }

    private BigDecimal balanceQuantity(Fixture fixture, UUID locationId) {
        return jdbcTemplate.queryForObject(
                "SELECT quantity FROM inventory_balances "
                        + "WHERE tenant_id = ? AND branch_id = ? AND product_id = ? AND location_id = ?",
                BigDecimal.class, fixture.tenantId(), fixture.branchId(), fixture.productId(), locationId);
    }

    private BigDecimal nullBalanceQuantity(Fixture fixture) {
        return jdbcTemplate.queryForObject(
                "SELECT quantity FROM inventory_balances "
                        + "WHERE tenant_id = ? AND branch_id = ? AND product_id = ? AND location_id IS NULL",
                BigDecimal.class, fixture.tenantId(), fixture.branchId(), fixture.productId());
    }

    private int countBalances(Fixture fixture) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory_balances "
                        + "WHERE tenant_id = ? AND branch_id = ? AND product_id = ?",
                Integer.class, fixture.tenantId(), fixture.branchId(), fixture.productId());
        return count == null ? 0 : count;
    }

    private Fixture createFixture(boolean locationsEnabled) {
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, slug, status, default_currency, timezone) "
                        + "VALUES (?, ?, ?, 'active', 'GTQ', 'America/Guatemala')",
                tenantId, "Tenant " + suffix, "tenant-" + suffix);
        jdbcTemplate.update(
                "INSERT INTO business_capabilities_configs "
                        + "(id, tenant_id, preset, supports_multiple_locations) VALUES (?, ?, 'custom', ?)",
                UUID.randomUUID(), tenantId, locationsEnabled);
        jdbcTemplate.update(
                "INSERT INTO branches (id, tenant_id, code, name, type, status) "
                        + "VALUES (?, ?, ?, ?, 'main', 'active')",
                branchId, tenantId, "MAIN-" + suffix, "Principal " + suffix);
        jdbcTemplate.update(
                "INSERT INTO categories (id, tenant_id, name, slug, status) VALUES (?, ?, ?, ?, 'active')",
                categoryId, tenantId, "Categoria " + suffix, "categoria-" + suffix);
        jdbcTemplate.update(
                "INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status) "
                        + "VALUES (?, ?, ?, 'Unidad', 'und', 'unit', true, 'active')",
                unitId, tenantId, "U-" + suffix.substring(0, 8));
        jdbcTemplate.update(
                "INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id, "
                        + "product_type, tracking_stock) VALUES (?, ?, ?, ?, ?, ?, 'physical', true)",
                productId, tenantId, "SKU-" + suffix, "Producto " + suffix, categoryId, unitId);
        return new Fixture(tenantId, branchId, productId);
    }

    private record Fixture(UUID tenantId, UUID branchId, UUID productId) {}
}

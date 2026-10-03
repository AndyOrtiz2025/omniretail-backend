package com.omniretail.backend.purchasing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.purchasing.dto.CreateGoodsReceiptRequest;
import com.omniretail.backend.purchasing.dto.GoodsReceiptItemRequest;
import com.omniretail.backend.purchasing.dto.GoodsReceiptResponse;
import com.omniretail.backend.purchasing.dto.TrackingDetailRequest;
import com.omniretail.backend.purchasing.dto.UpdateGoodsReceiptRequest;
import com.omniretail.backend.purchasing.entity.GoodsReceiptStatus;
import com.omniretail.backend.purchasing.entity.PurchaseOrderStatus;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.PermissionResolver;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantEntitlementResolver;
import com.omniretail.backend.shared.security.TenantEntitlements;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
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
class GoodsReceiptServiceTest {

    @Autowired private GoodsReceiptService service;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;
    @MockitoBean private PermissionResolver permissions;
    @MockitoBean private TenantEntitlementResolver entitlements;

    @BeforeEach
    void setUp() {
        given(permissions.hasPermission(any(), any(), anyString())).willReturn(true);
        given(entitlements.resolve(any())).willReturn(
                new TenantEntitlements(true, true, EnumSet.allOf(SaasCapability.class)));
    }

    @Test
    void createDraftDerivesSnapshotsAndBranchWithoutInventoryOrPoMutation() {
        Fixture fixture = fixture(true, false, true, "approved", "2");

        GoodsReceiptResponse receipt = create(fixture, fixture.orderItem(), "3", fixture.location());

        assertThat(receipt.number()).isEqualTo("REC-001");
        assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.draft);
        assertThat(receipt.branchId()).isEqualTo(fixture.branch());
        assertThat(receipt.purchaseOrderNumber()).isEqualTo("OC-TEST");
        assertThat(receipt.items()).singleElement().satisfies(item -> {
            assertThat(item.productId()).isEqualTo(fixture.product());
            assertThat(item.productNameSnapshot()).isEqualTo("Producto histórico");
            assertThat(item.receivedQuantity()).isEqualByComparingTo("3");
            assertThat(item.purchaseToBaseFactor()).isEqualByComparingTo("2");
            assertThat(item.baseQuantity()).isEqualByComparingTo("6");
            assertThat(item.unitCost()).isEqualByComparingTo("9.50");
            assertThat(item.trackingDetails()).isEmpty();
        });
        assertThat(count("inventory_movements", fixture.tenant())).isZero();
        assertThat(count("inventory_balances", fixture.tenant())).isZero();
        assertThat(orderStatus(fixture.order())).isEqualTo("approved");
    }

    @Test
    void updateReplacesWholeDraftAndDeleteRemovesHeaderAndItems() {
        Fixture fixture = fixture(true, false, true, "approved", "1");
        GoodsReceiptResponse receipt = create(fixture, fixture.orderItem(), "2", fixture.location());

        GoodsReceiptResponse updated = service.update(
                receipt.id(),
                new UpdateGoodsReceiptRequest(
                        "  actualizado  ",
                        List.of(new GoodsReceiptItemRequest(
                                fixture.orderItem(), new BigDecimal("4"), fixture.location()))));

        assertThat(updated.notes()).isEqualTo("actualizado");
        assertThat(updated.items()).singleElement().satisfies(item ->
                assertThat(item.receivedQuantity()).isEqualByComparingTo("4"));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM goods_receipt_items WHERE goods_receipt_id = ?",
                        Long.class,
                        receipt.id()))
                .isOne();

        service.delete(receipt.id());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM goods_receipts WHERE id = ?", Long.class, receipt.id()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM goods_receipt_items WHERE goods_receipt_id = ?",
                        Long.class,
                        receipt.id()))
                .isZero();
    }

    @Test
    void confirmCreatesLocationMovementAndMarksReceiptAndPoReceived() {
        Fixture fixture = fixture(true, false, true, "approved", "2");
        GoodsReceiptResponse draft = create(fixture, fixture.orderItem(), "10", fixture.location());

        GoodsReceiptResponse confirmed = service.confirm(draft.id());

        assertThat(confirmed.status()).isEqualTo(GoodsReceiptStatus.confirmed);
        assertThat(confirmed.receivedAt()).isNotNull();
        assertThat(confirmed.receivedByUserId()).isEqualTo(fixture.user());
        assertThat(orderStatus(fixture.order())).isEqualTo("received");
        assertThat(balance(fixture, fixture.product(), fixture.location())).isEqualByComparingTo("20");
        assertThat(jdbc.queryForMap(
                        """
                        SELECT type, reason, reference_type, reference_id, reference_line_id,
                               from_location_id, to_location_id, performed_by_user_id, quantity
                        FROM inventory_movements WHERE tenant_id = ?
                        """,
                        fixture.tenant()))
                .containsEntry("type", "in")
                .containsEntry("reason", "Recepción de orden de compra")
                .containsEntry("reference_type", "goods_receipt")
                .containsEntry("reference_id", draft.id())
                .containsEntry("reference_line_id", draft.items().getFirst().id())
                .containsEntry("from_location_id", null)
                .containsEntry("to_location_id", fixture.location())
                .containsEntry("performed_by_user_id", fixture.user());
        assertThat(count("inventory_movement_traces", fixture.tenant())).isZero();
        assertThatThrownBy(() -> service.update(
                        draft.id(),
                        new UpdateGoodsReceiptRequest(
                                null,
                                List.of(new GoodsReceiptItemRequest(
                                        fixture.orderItem(), BigDecimal.ONE, fixture.location())))))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("GOODS_RECEIPT_INVALID_STATUS"));
        assertThatThrownBy(() -> service.delete(draft.id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("GOODS_RECEIPT_INVALID_STATUS"));
    }

    @Test
    void partialReceiptsIgnoreDraftsAndOverReceivingIsRejectedPerPoItem() {
        Fixture fixture = fixture(true, false, true, "approved", "1");
        GoodsReceiptResponse first = create(fixture, fixture.orderItem(), "4", fixture.location());
        GoodsReceiptResponse ignoredDraft = create(fixture, fixture.orderItem(), "5", fixture.location());
        service.confirm(first.id());
        assertThat(orderStatus(fixture.order())).isEqualTo("partially_received");

        GoodsReceiptResponse completing = create(fixture, fixture.orderItem(), "6", fixture.location());
        service.confirm(completing.id());
        assertThat(orderStatus(fixture.order())).isEqualTo("received");
        assertThat(receiptStatus(ignoredDraft.id())).isEqualTo("draft");

        jdbc.update("UPDATE purchase_orders SET status = 'partially_received' WHERE id = ?", fixture.order());
        GoodsReceiptResponse excess = create(fixture, fixture.orderItem(), "1", fixture.location());
        assertThatThrownBy(() -> service.confirm(excess.id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("GOODS_RECEIPT_OVER_RECEIVING"));
        assertThat(receiptStatus(excess.id())).isEqualTo("draft");
        assertThat(sumMovements(fixture.tenant())).isEqualByComparingTo("10");
    }

    @Test
    void nonStockProductConfirmsCommerciallyWithoutLocationBalanceOrMovement() {
        Fixture fixture = fixture(false, false, true, "approved", "3");
        GoodsReceiptResponse draft = create(fixture, fixture.orderItem(), "10", UUID.randomUUID());

        assertThat(draft.items()).singleElement().extracting(item -> item.locationId()).isNull();
        service.confirm(draft.id());

        assertThat(orderStatus(fixture.order())).isEqualTo("received");
        assertThat(count("inventory_balances", fixture.tenant())).isZero();
        assertThat(count("inventory_movements", fixture.tenant())).isZero();
    }

    @Test
    void usesPoFactorSnapshotAndEnforcesQuantityAndIntegerRules() {
        Fixture fixture = fixture(true, false, false, "approved", "2");
        jdbc.update("UPDATE supplier_products SET purchase_to_base_factor = 99 WHERE id = ?", fixture.supplierProduct());

        GoodsReceiptResponse receipt = create(fixture, fixture.orderItem(), "2", fixture.location());
        assertThat(receipt.items()).singleElement().extracting(item -> item.baseQuantity())
                .isEqualTo(new BigDecimal("4.000"));

        assertThatThrownBy(() -> create(fixture, fixture.orderItem(), "1.5", fixture.location()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("GOODS_RECEIPT_INVALID_QUANTITY"));
        for (String invalid : List.of("0", "-1", "0.0001", "1000000000")) {
            assertThatThrownBy(() -> create(fixture, fixture.orderItem(), invalid, fixture.location()))
                    .isInstanceOfSatisfying(BusinessException.class,
                            exception -> assertThat(exception.getCode()).isEqualTo("GOODS_RECEIPT_INVALID_QUANTITY"));
        }

        Fixture serial = fixture(true, true, true, "approved", "0.5");
        assertThatThrownBy(() -> create(serial, serial.orderItem(), "1", serial.location()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("GOODS_RECEIPT_INVALID_BASE_QUANTITY"));
        Fixture integerBase = fixture(true, false, true, "approved", "0.5");
        jdbc.update("UPDATE units SET allows_decimals = false WHERE id = ?", integerBase.baseUnit());
        assertThatThrownBy(() -> create(integerBase, integerBase.orderItem(), "1", integerBase.location()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("GOODS_RECEIPT_INVALID_BASE_QUANTITY"));
    }

    @Test
    void repeatedProductOnDifferentPoItemsIsAccountedIndependently() {
        Fixture fixture = fixture(true, false, true, "approved", "1");
        UUID secondItem = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO purchase_order_items
                    (id, tenant_id, purchase_order_id, supplier_product_id, product_id,
                     product_name_snapshot, product_sku_snapshot, quantity, unit_id,
                     unit_symbol_snapshot, purchase_to_base_factor, unit_cost, subtotal)
                VALUES (?, ?, ?, ?, ?, 'Producto histórico', 'SKU-HIST', 4, ?, 'cj', 1, 9.50, 38)
                """, secondItem, fixture.tenant(), fixture.order(), fixture.supplierProduct(),
                fixture.product(), fixture.purchaseUnit());
        GoodsReceiptResponse receipt = service.create(new CreateGoodsReceiptRequest(
                fixture.order(),
                null,
                List.of(
                        new GoodsReceiptItemRequest(
                                fixture.orderItem(), new BigDecimal("10"), fixture.location()),
                        new GoodsReceiptItemRequest(secondItem, new BigDecimal("4"), fixture.location()))));

        service.confirm(receipt.id());

        assertThat(orderStatus(fixture.order())).isEqualTo("received");
        assertThat(balance(fixture, fixture.product(), fixture.location())).isEqualByComparingTo("14");
        assertThat(count("inventory_movements", fixture.tenant())).isEqualTo(2);
    }

    @Test
    void validatesPoStateItemOwnershipDuplicatesLocationAndTenantIsolation() {
        Fixture fixture = fixture(true, false, true, "approved", "1");
        assertThatThrownBy(() -> create(fixture, fixture.orderItem(), "1", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("GOODS_RECEIPT_LOCATION_REQUIRED"));
        assertThatThrownBy(() -> service.create(new CreateGoodsReceiptRequest(
                        fixture.order(),
                        null,
                        List.of(
                                new GoodsReceiptItemRequest(fixture.orderItem(), BigDecimal.ONE, fixture.location()),
                                new GoodsReceiptItemRequest(fixture.orderItem(), BigDecimal.ONE, fixture.location())))))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("GOODS_RECEIPT_DUPLICATE_PO_ITEM"));

        Fixture other = fixture(true, false, true, "approved", "1");
        assertThatThrownBy(() -> create(fixture, other.orderItem(), "1", fixture.location()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("GOODS_RECEIPT_PO_ITEM_NOT_FOUND"));

        GoodsReceiptResponse crossTenant = create(other, other.orderItem(), "1", other.location());
        useActor(fixture);
        assertThatThrownBy(() -> service.get(crossTenant.id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("GOODS_RECEIPT_NOT_FOUND"));
    }

    @Test
    void locationTenantBranchAndStatusAreValidatedBeforePersistingDraft() {
        Fixture fixture = fixture(true, false, true, "approved", "1");
        Fixture otherTenant = fixture(true, false, true, "approved", "1");
        assertThatThrownBy(() -> create(
                        fixture, fixture.orderItem(), "1", otherTenant.location()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("LOCATION_NOT_FOUND"));

        UUID otherBranch = addBranch(fixture.tenant());
        UUID wrongBranchLocation = addLocation(fixture.tenant(), otherBranch, "active");
        assertThatThrownBy(() -> create(
                        fixture, fixture.orderItem(), "1", wrongBranchLocation))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("LOCATION_BRANCH_MISMATCH"));

        for (String status : List.of("inactive", "archived")) {
            assertThatThrownBy(() -> create(
                            fixture,
                            fixture.orderItem(),
                            "1",
                            addLocation(fixture.tenant(), fixture.branch(), status)))
                    .isInstanceOfSatisfying(BusinessException.class,
                            exception -> assertThat(exception.getCode()).isEqualTo("LOCATION_NOT_ACTIVE"));
        }
        assertThat(count("inventory_movements", fixture.tenant())).isZero();
    }

    @Test
    void acceptsReceivablePoStatesRejectsOthersAndListsWithRealPaginationAndFilters() {
        for (String accepted : List.of("approved", "sent", "partially_received")) {
            Fixture fixture = fixture(false, false, true, accepted, "1");
            assertThat(create(fixture, fixture.orderItem(), "1", null).status())
                    .isEqualTo(GoodsReceiptStatus.draft);
        }
        for (String rejected : List.of("draft", "pending_approval", "cancelled", "received")) {
            Fixture fixture = fixture(false, false, true, rejected, "1");
            assertThatThrownBy(() -> create(fixture, fixture.orderItem(), "1", null))
                    .isInstanceOfSatisfying(BusinessException.class,
                            exception -> assertThat(exception.getCode()).isEqualTo("GOODS_RECEIPT_PO_NOT_RECEIVABLE"));
        }

        Fixture cancelledAfterDraft = fixture(true, false, true, "approved", "1");
        GoodsReceiptResponse waiting = create(
                cancelledAfterDraft,
                cancelledAfterDraft.orderItem(),
                "1",
                cancelledAfterDraft.location());
        jdbc.update("UPDATE purchase_orders SET status = 'cancelled' WHERE id = ?", cancelledAfterDraft.order());
        assertThatThrownBy(() -> service.confirm(waiting.id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("GOODS_RECEIPT_PO_NOT_RECEIVABLE"));

        Fixture fixture = fixture(false, false, true, "approved", "1");
        create(fixture, fixture.orderItem(), "1", null);
        create(fixture, fixture.orderItem(), "1", null);
        assertThat(service.list(
                                fixture.branch(), fixture.order(), GoodsReceiptStatus.draft, PageRequest.of(0, 1))
                        .items())
                .hasSize(1);
        assertThat(service.list(
                                fixture.branch(), fixture.order(), GoodsReceiptStatus.draft, PageRequest.of(0, 1))
                        .totalItems())
                .isEqualTo(2);
    }

    @Test
    void receivingRbacCapabilityAndHistoricalReadSemanticsAreEnforced() {
        Fixture fixture = fixture(false, false, true, "approved", "1");
        GoodsReceiptResponse receipt = create(fixture, fixture.orderItem(), "1", null);

        given(permissions.hasPermission(any(), any(), anyString())).willAnswer(invocation ->
                invocation.getArgument(2, String.class).equals("receiving.receipts.confirm"));
        assertThat(create(fixture, fixture.orderItem(), "1", null).status())
                .isEqualTo(GoodsReceiptStatus.draft);
        given(permissions.hasPermission(any(), any(), anyString())).willReturn(true);

        given(entitlements.resolve(fixture.tenant())).willReturn(
                new TenantEntitlements(true, true, EnumSet.of(SaasCapability.inventory)));
        assertThat(service.get(receipt.id()).id()).isEqualTo(receipt.id());
        assertThatThrownBy(() -> create(fixture, fixture.orderItem(), "1", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("CAPABILITY_REQUIRED"));

        given(permissions.hasPermission(any(), any(), anyString())).willReturn(false);
        assertThatThrownBy(() -> service.get(receipt.id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("ACCESS_DENIED"));
        given(permissions.hasPermission(any(), any(), anyString())).willAnswer(invocation ->
                invocation.getArgument(2, String.class).equals("receiving.receipts.confirm"));
        assertThat(service.get(receipt.id()).id()).isEqualTo(receipt.id());

        jdbc.update("UPDATE roles SET branch_scope = 'selected' WHERE id = ?", fixture.role());
        jdbc.update("UPDATE users SET allowed_branch_ids = '{}'::uuid[] WHERE id = ?", fixture.user());
        assertThatThrownBy(() -> service.get(receipt.id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("BRANCH_ACCESS_DENIED"));
    }

    @Test
    void laterItemLocationFailureRollsBackEarlierStockReceiptAndPoStatus() {
        Fixture fixture = fixture(true, false, true, "approved", "1");
        UUID secondProduct = addProductAndOrderItem(fixture, true);
        UUID secondItem = jdbc.queryForObject(
                "SELECT id FROM purchase_order_items WHERE purchase_order_id = ? AND product_id = ?",
                UUID.class,
                fixture.order(),
                secondProduct);
        UUID locationBecomingInactive = addLocation(fixture.tenant(), fixture.branch(), "active");
        GoodsReceiptResponse draft = service.create(new CreateGoodsReceiptRequest(
                fixture.order(),
                null,
                List.of(
                        new GoodsReceiptItemRequest(fixture.orderItem(), BigDecimal.ONE, fixture.location()),
                        new GoodsReceiptItemRequest(secondItem, BigDecimal.ONE, locationBecomingInactive))));
        jdbc.update("UPDATE locations SET status = 'inactive' WHERE id = ?", locationBecomingInactive);

        assertThatThrownBy(() -> service.confirm(draft.id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("LOCATION_NOT_ACTIVE"));
        assertThat(receiptStatus(draft.id())).isEqualTo("draft");
        assertThat(orderStatus(fixture.order())).isEqualTo("approved");
        assertThat(count("inventory_balances", fixture.tenant())).isZero();
        assertThat(count("inventory_movements", fixture.tenant())).isZero();
    }

    @Test
    void traceableDraftPersistsReturnsAndReplacesCanonicalDetailsWithoutStockMutation() {
        Fixture fixture = fixture(true, false, true, "approved", "2");
        jdbc.update(
                "UPDATE products SET tracking_lot = true WHERE id = ?",
                fixture.product());
        assertThatThrownBy(() -> createTracked(
                        fixture,
                        fixture.orderItem(),
                        "3",
                        List.of(detail("3.000", "PURCHASE-QUANTITY", null, List.of()))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("TRACKING_QUANTITY_MISMATCH"));
        assertThatThrownBy(() -> createTracked(
                        fixture,
                        fixture.orderItem(),
                        "3",
                        List.of(
                                detail("3.000", "DUPLICATE-LOT", null, List.of()),
                                detail("3.000", "DUPLICATE-LOT", null, List.of()))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("DUPLICATE_LOT_DETAIL"));
        GoodsReceiptResponse draft = createTracked(
                fixture,
                fixture.orderItem(),
                "3",
                List.of(
                        detail("2.000", "LOT-B", null, List.of()),
                        detail("4.000", " LOT-A ", null, List.of())));

        assertThat(draft.items()).singleElement().satisfies(item -> {
            assertThat(item.baseQuantity()).isEqualByComparingTo("6.000");
            assertThat(item.trackingDetails())
                    .extracting(TrackingDetailRequest::lotNumber)
                    .containsExactly("LOT-A", "LOT-B");
        });
        assertThat(jdbc.queryForObject(
                        "SELECT tracking_details::text FROM goods_receipt_items WHERE id = ?",
                        String.class,
                        draft.items().getFirst().id()))
                .contains("LOT-A", "LOT-B");
        assertThat(count("inventory_balances", fixture.tenant())).isZero();
        assertThat(count("inventory_lots", fixture.tenant())).isZero();

        GoodsReceiptResponse updated = service.update(
                draft.id(),
                new UpdateGoodsReceiptRequest(
                        null,
                        List.of(new GoodsReceiptItemRequest(
                                fixture.orderItem(),
                                new BigDecimal("3"),
                                fixture.location(),
                                List.of(detail("6.000", "LOT-C", null, List.of()))))));
        assertThat(updated.items().getFirst().trackingDetails())
                .extracting(TrackingDetailRequest::lotNumber)
                .containsExactly("LOT-C");
        assertThat(service.get(draft.id()).items().getFirst().trackingDetails())
                .extracting(TrackingDetailRequest::lotNumber)
                .containsExactly("LOT-C");
        assertThat(count("inventory_movements", fixture.tenant())).isZero();
    }

    @Test
    void confirmRevalidatesPersistedTrackingDetailsAgainstCurrentProductSettings() {
        Fixture fixture = fixture(true, false, true, "approved", "1");
        jdbc.update("UPDATE products SET tracking_lot = true WHERE id = ?", fixture.product());
        GoodsReceiptResponse draft = createTracked(
                fixture,
                fixture.orderItem(),
                "2",
                List.of(detail("2.000", "DRAFT-LOT", null, List.of())));
        jdbc.update("UPDATE products SET tracking_lot = false WHERE id = ?", fixture.product());

        assertThatThrownBy(() -> service.confirm(draft.id()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("INVALID_TRACKING_PAYLOAD"));
        assertThat(receiptStatus(draft.id())).isEqualTo("draft");
        assertThat(count("inventory_balances", fixture.tenant())).isZero();
        assertThat(count("inventory_lots", fixture.tenant())).isZero();
        assertThat(count("inventory_movements", fixture.tenant())).isZero();
    }

    @Test
    void multipleLotsCreateOneMovementAndLotTracesSumToBaseQuantity() {
        Fixture fixture = fixture(true, false, true, "approved", "1");
        jdbc.update("UPDATE products SET tracking_lot = true WHERE id = ?", fixture.product());
        GoodsReceiptResponse draft = createTracked(
                fixture,
                fixture.orderItem(),
                "10",
                List.of(
                        detail("6.000", "LOT-A", null, List.of()),
                        detail("4.000", "LOT-B", null, List.of())));

        service.confirm(draft.id());

        assertThat(count("inventory_movements", fixture.tenant())).isOne();
        assertThat(count("inventory_lots", fixture.tenant())).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT sum(quantity) FROM inventory_lot_balances WHERE tenant_id = ?",
                        BigDecimal.class,
                        fixture.tenant()))
                .isEqualByComparingTo("10.000");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM inventory_movement_traces WHERE tenant_id = ? AND lot_id IS NOT NULL AND serial_id IS NULL",
                        Long.class,
                        fixture.tenant()))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                        "SELECT sum(quantity) FROM inventory_movement_traces WHERE tenant_id = ?",
                        BigDecimal.class,
                        fixture.tenant()))
                .isEqualByComparingTo("10.000");
    }

    @Test
    void existingLotIsReusedAndExpirationMismatchRollsBackSecondReceipt() {
        Fixture fixture = fixture(true, false, true, "approved", "1");
        jdbc.update(
                "UPDATE products SET tracking_lot = true, tracking_expiration = true WHERE id = ?",
                fixture.product());
        LocalDate expiration = LocalDate.now().plusDays(30);
        GoodsReceiptResponse first = createTracked(
                fixture,
                fixture.orderItem(),
                "4",
                List.of(detail("4.000", "EXP-LOT", expiration, List.of())));
        service.confirm(first.id());
        GoodsReceiptResponse matching = createTracked(
                fixture,
                fixture.orderItem(),
                "2",
                List.of(detail("2.000", "EXP-LOT", expiration, List.of())));
        service.confirm(matching.id());
        GoodsReceiptResponse conflicting = createTracked(
                fixture,
                fixture.orderItem(),
                "2",
                List.of(detail("2.000", "EXP-LOT", expiration.plusDays(1), List.of())));

        assertThatThrownBy(() -> service.confirm(conflicting.id()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("LOT_EXPIRATION_MISMATCH"));
        assertThat(count("inventory_lots", fixture.tenant())).isOne();
        assertThat(balance(fixture, fixture.product(), fixture.location()))
                .isEqualByComparingTo("6.000");
        assertThat(count("inventory_movements", fixture.tenant())).isEqualTo(2);
        assertThat(receiptStatus(conflicting.id())).isEqualTo("draft");
    }

    @Test
    void serialReceiptCreatesAvailableSerialsAndPersistedDuplicateRollsBack() {
        Fixture fixture = fixture(true, true, true, "approved", "1");
        GoodsReceiptResponse first = createTracked(
                fixture,
                fixture.orderItem(),
                "2",
                List.of(detail("2.000", null, null, List.of("SER-2", "SER-1"))));
        service.confirm(first.id());

        assertThat(jdbc.queryForList(
                        "SELECT serial_number FROM inventory_serials WHERE tenant_id = ? ORDER BY serial_number",
                        String.class,
                        fixture.tenant()))
                .containsExactly("SER-1", "SER-2");
        assertThat(jdbc.queryForList(
                        "SELECT status FROM inventory_serials WHERE tenant_id = ? ORDER BY serial_number",
                        String.class,
                        fixture.tenant()))
                .containsOnly("AVAILABLE");
        assertThat(count("inventory_movement_traces", fixture.tenant())).isEqualTo(2);

        GoodsReceiptResponse duplicate = createTracked(
                fixture,
                fixture.orderItem(),
                "1",
                List.of(detail("1.000", null, null, List.of("SER-1"))));
        assertThatThrownBy(() -> service.confirm(duplicate.id()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("DUPLICATE_SERIAL"));
        assertThat(balance(fixture, fixture.product(), fixture.location()))
                .isEqualByComparingTo("2.000");
        assertThat(count("inventory_movements", fixture.tenant())).isOne();
        assertThat(receiptStatus(duplicate.id())).isEqualTo("draft");
    }

    @Test
    void serialValidationRejectsCountAndDuplicatesWithinOrAcrossDetails() {
        Fixture fixture = fixture(true, true, true, "approved", "1");

        assertThatThrownBy(() -> createTracked(
                        fixture,
                        fixture.orderItem(),
                        "2",
                        List.of(detail("2.000", null, null, List.of("ONLY-ONE")))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("SERIAL_COUNT_MISMATCH"));
        assertThatThrownBy(() -> createTracked(
                        fixture,
                        fixture.orderItem(),
                        "2",
                        List.of(detail("2.000", null, null, List.of("DUP", "DUP")))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("DUPLICATE_SERIAL"));
        assertThatThrownBy(() -> createTracked(
                        fixture,
                        fixture.orderItem(),
                        "2",
                        List.of(
                                detail("1.000", null, null, List.of("CROSS")),
                                detail("1.000", null, null, List.of("CROSS")))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("DUPLICATE_SERIAL"));
        assertThat(count("goods_receipts", fixture.tenant())).isZero();
    }

    @Test
    void lotAndSerialReceiptLinksEachSerialAndCreatesOnlySerialTraces() {
        Fixture fixture = fixture(true, true, true, "approved", "1");
        jdbc.update("UPDATE products SET tracking_lot = true WHERE id = ?", fixture.product());
        GoodsReceiptResponse draft = createTracked(
                fixture,
                fixture.orderItem(),
                "3",
                List.of(
                        detail("2.000", "LOT-A", null, List.of("A-1", "A-2")),
                        detail("1.000", "LOT-B", null, List.of("B-1"))));

        service.confirm(draft.id());

        assertThat(count("inventory_movements", fixture.tenant())).isOne();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM inventory_serials s JOIN inventory_lots l ON l.id = s.lot_id WHERE s.tenant_id = ? AND ((s.serial_number LIKE 'A-%' AND l.lot_number = 'LOT-A') OR (s.serial_number = 'B-1' AND l.lot_number = 'LOT-B'))",
                        Long.class,
                        fixture.tenant()))
                .isEqualTo(3L);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM inventory_movement_traces WHERE tenant_id = ? AND serial_id IS NOT NULL AND lot_id IS NULL",
                        Long.class,
                        fixture.tenant()))
                .isEqualTo(3L);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM inventory_movement_traces WHERE tenant_id = ? AND lot_id IS NOT NULL",
                        Long.class,
                        fixture.tenant()))
                .isZero();
    }

    @Test
    void duplicateSerialInLaterDeterministicItemRollsBackEarlierLotMutation() {
        Fixture fixture = fixture(true, false, true, "approved", "1");
        UUID secondProduct = addProductAndOrderItem(fixture, true);
        UUID secondItem = jdbc.queryForObject(
                "SELECT id FROM purchase_order_items WHERE purchase_order_id = ? AND product_id = ?",
                UUID.class,
                fixture.order(),
                secondProduct);
        UUID lotProduct = fixture.product().compareTo(secondProduct) < 0
                ? fixture.product()
                : secondProduct;
        UUID lotItem = lotProduct.equals(fixture.product()) ? fixture.orderItem() : secondItem;
        UUID serialProduct = lotProduct.equals(fixture.product()) ? secondProduct : fixture.product();
        UUID serialItem = serialProduct.equals(fixture.product()) ? fixture.orderItem() : secondItem;
        jdbc.update(
                "UPDATE products SET tracking_lot = true, tracking_serial = false WHERE id = ?",
                lotProduct);
        jdbc.update(
                "UPDATE products SET tracking_lot = false, tracking_serial = true WHERE id = ?",
                serialProduct);
        jdbc.update(
                """
                INSERT INTO inventory_serials
                    (id, tenant_id, branch_id, location_id, product_id, serial_number, status, version)
                VALUES (?, ?, ?, ?, ?, 'PERSISTED-DUP', 'AVAILABLE', 0)
                """,
                UUID.randomUUID(),
                fixture.tenant(),
                fixture.branch(),
                fixture.location(),
                serialProduct);
        GoodsReceiptResponse draft = service.create(new CreateGoodsReceiptRequest(
                fixture.order(),
                null,
                List.of(
                        new GoodsReceiptItemRequest(
                                serialItem,
                                BigDecimal.ONE,
                                fixture.location(),
                                List.of(detail("1.000", null, null, List.of("PERSISTED-DUP")))),
                        new GoodsReceiptItemRequest(
                                lotItem,
                                BigDecimal.ONE,
                                fixture.location(),
                                List.of(detail("1.000", "ROLLBACK-LOT", null, List.of()))))));

        assertThatThrownBy(() -> service.confirm(draft.id()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("DUPLICATE_SERIAL"));
        assertThat(count("inventory_lots", fixture.tenant())).isZero();
        assertThat(count("inventory_balances", fixture.tenant())).isZero();
        assertThat(count("inventory_movements", fixture.tenant())).isZero();
        assertThat(receiptStatus(draft.id())).isEqualTo("draft");
        assertThat(orderStatus(fixture.order())).isEqualTo("approved");
    }

    private GoodsReceiptResponse create(Fixture fixture, UUID orderItem, String quantity, UUID location) {
        useActor(fixture);
        return service.create(new CreateGoodsReceiptRequest(
                fixture.order(),
                "Recepción test",
                List.of(new GoodsReceiptItemRequest(orderItem, new BigDecimal(quantity), location))));
    }

    private GoodsReceiptResponse createTracked(
            Fixture fixture,
            UUID orderItem,
            String quantity,
            List<TrackingDetailRequest> trackingDetails) {
        useActor(fixture);
        return service.create(new CreateGoodsReceiptRequest(
                fixture.order(),
                "Recepcion trazable",
                List.of(new GoodsReceiptItemRequest(
                        orderItem,
                        new BigDecimal(quantity),
                        fixture.location(),
                        trackingDetails))));
    }

    private static TrackingDetailRequest detail(
            String baseQuantity,
            String lotNumber,
            LocalDate expirationDate,
            List<String> serialNumbers) {
        return new TrackingDetailRequest(
                new BigDecimal(baseQuantity), lotNumber, expirationDate, serialNumbers);
    }

    private void useActor(Fixture fixture) {
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                fixture.user(),
                fixture.tenant(),
                UserType.employee,
                fixture.role(),
                fixture.branch(),
                UUID.randomUUID()));
    }

    private Fixture fixture(
            boolean trackingStock,
            boolean trackingSerial,
            boolean purchaseUnitAllowsDecimals,
            String orderStatus,
            String factor) {
        UUID tenant = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        UUID role = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID baseUnit = UUID.randomUUID();
        UUID purchaseUnit = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID supplier = UUID.randomUUID();
        UUID supplierProduct = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID orderItem = UUID.randomUUID();
        UUID location = addCore(
                tenant,
                branch,
                role,
                user,
                category,
                baseUnit,
                purchaseUnit,
                product,
                supplier,
                supplierProduct,
                order,
                orderItem,
                trackingStock,
                trackingSerial,
                purchaseUnitAllowsDecimals,
                orderStatus,
                factor);
        Fixture fixture = new Fixture(
                tenant,
                branch,
                role,
                user,
                category,
                baseUnit,
                purchaseUnit,
                product,
                supplier,
                supplierProduct,
                order,
                orderItem,
                location);
        useActor(fixture);
        return fixture;
    }

    private UUID addCore(
            UUID tenant,
            UUID branch,
            UUID role,
            UUID user,
            UUID category,
            UUID baseUnit,
            UUID purchaseUnit,
            UUID product,
            UUID supplier,
            UUID supplierProduct,
            UUID order,
            UUID orderItem,
            boolean trackingStock,
            boolean trackingSerial,
            boolean purchaseUnitAllowsDecimals,
            String orderStatus,
            String factor) {
        jdbc.update("INSERT INTO tenants (id, name, slug) VALUES (?, 'Receiving test', ?)", tenant, "rec-" + tenant);
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, 'Principal', 'main', 'active')
                """, branch, tenant, "B-" + branch.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO roles (id, tenant_id, name, branch_scope, permissions)
                VALUES (?, ?, ?, 'all', '{}')
                """, role, tenant, "Rol " + role);
        jdbc.update("""
                INSERT INTO users (id, tenant_id, name, email, type, status, role_id, branch_id)
                VALUES (?, ?, 'Receptor', ?, 'employee', 'active', ?, ?)
                """, user, tenant, user + "@test.local", role, branch);
        jdbc.update("INSERT INTO categories (id, tenant_id, name, slug) VALUES (?, ?, 'Cat', ?)",
                category, tenant, "cat-" + category);
        jdbc.update("""
                INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Base', 'u', 'unit', true, 'active'),
                       (?, ?, ?, 'Compra', 'cj', 'unit', ?, 'active')
                """, baseUnit, tenant, "U-" + baseUnit.toString().substring(0, 8),
                purchaseUnit, tenant, "C-" + purchaseUnit.toString().substring(0, 8),
                purchaseUnitAllowsDecimals);
        jdbc.update("""
                INSERT INTO products
                    (id, tenant_id, sku, name, category_id, base_unit_id, tracking_stock, tracking_serial)
                VALUES (?, ?, ?, 'Producto histórico', ?, ?, ?, ?)
                """, product, tenant, "SKU-" + product, category, baseUnit, trackingStock, trackingSerial);
        jdbc.update("INSERT INTO suppliers (id, tenant_id, name, status) VALUES (?, ?, 'Proveedor', 'active')",
                supplier, tenant);
        jdbc.update("""
                INSERT INTO supplier_products
                    (id, tenant_id, supplier_id, product_id, purchase_unit_id,
                     purchase_to_base_factor, last_cost, lead_time_days, minimum_order_quantity)
                VALUES (?, ?, ?, ?, ?, ?::numeric, 9.50, 0, 1)
                """, supplierProduct, tenant, supplier, product, purchaseUnit, factor);
        jdbc.update("""
                INSERT INTO purchase_orders
                    (id, tenant_id, branch_id, number, supplier_id, supplier_name_snapshot, status,
                     subtotal, total, created_by_user_id)
                VALUES (?, ?, ?, 'OC-TEST', ?, 'Proveedor', ?, 95, 95, ?)
                """, order, tenant, branch, supplier, orderStatus, user);
        jdbc.update("""
                INSERT INTO purchase_order_items
                    (id, tenant_id, purchase_order_id, supplier_product_id, product_id,
                     product_name_snapshot, product_sku_snapshot, quantity, unit_id,
                     unit_symbol_snapshot, purchase_to_base_factor, unit_cost, subtotal)
                VALUES (?, ?, ?, ?, ?, 'Producto histórico', 'SKU-HIST', 10, ?, 'cj', ?::numeric, 9.50, 95)
                """, orderItem, tenant, order, supplierProduct, product, purchaseUnit, factor);
        return addLocation(tenant, branch, "active");
    }

    private UUID addProductAndOrderItem(Fixture fixture, boolean trackingStock) {
        UUID product = UUID.randomUUID();
        UUID supplierProduct = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id, tracking_stock)
                VALUES (?, ?, ?, 'Segundo producto', ?, ?, ?)
                """, product, fixture.tenant(), "SKU-" + product, fixture.category(), fixture.baseUnit(), trackingStock);
        jdbc.update("""
                INSERT INTO supplier_products
                    (id, tenant_id, supplier_id, product_id, purchase_unit_id,
                     purchase_to_base_factor, last_cost, lead_time_days, minimum_order_quantity)
                VALUES (?, ?, ?, ?, ?, 1, 1, 0, 1)
                """, supplierProduct, fixture.tenant(), fixture.supplier(), product, fixture.purchaseUnit());
        jdbc.update("""
                INSERT INTO purchase_order_items
                    (id, tenant_id, purchase_order_id, supplier_product_id, product_id,
                     product_name_snapshot, product_sku_snapshot, quantity, unit_id,
                     unit_symbol_snapshot, purchase_to_base_factor, unit_cost, subtotal)
                VALUES (?, ?, ?, ?, ?, 'Segundo', 'SKU-2', 10, ?, 'cj', 1, 1, 10)
                """, item, fixture.tenant(), fixture.order(), supplierProduct, product, fixture.purchaseUnit());
        return product;
    }

    private UUID addLocation(UUID tenant, UUID branch, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status)
                VALUES (?, ?, ?, ?, 'Bodega', 'warehouse', ?)
                """, id, tenant, branch, "L-" + id.toString().substring(0, 8), status);
        return id;
    }

    private UUID addBranch(UUID tenant) {
        UUID branch = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, 'Secundaria', 'store', 'active')
                """, branch, tenant, "B-" + branch.toString().substring(0, 8));
        return branch;
    }

    private long count(String table, UUID tenant) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Long.class, tenant);
    }

    private String orderStatus(UUID order) {
        return jdbc.queryForObject("SELECT status FROM purchase_orders WHERE id = ?", String.class, order);
    }

    private String receiptStatus(UUID receipt) {
        return jdbc.queryForObject("SELECT status FROM goods_receipts WHERE id = ?", String.class, receipt);
    }

    private BigDecimal balance(Fixture fixture, UUID product, UUID location) {
        return jdbc.queryForObject("""
                SELECT quantity FROM inventory_balances
                WHERE tenant_id = ? AND branch_id = ? AND product_id = ? AND location_id = ?
                """, BigDecimal.class, fixture.tenant(), fixture.branch(), product, location);
    }

    private BigDecimal sumMovements(UUID tenant) {
        return jdbc.queryForObject(
                "SELECT COALESCE(sum(quantity), 0) FROM inventory_movements WHERE tenant_id = ?",
                BigDecimal.class,
                tenant);
    }

    private record Fixture(
            UUID tenant,
            UUID branch,
            UUID role,
            UUID user,
            UUID category,
            UUID baseUnit,
            UUID purchaseUnit,
            UUID product,
            UUID supplier,
            UUID supplierProduct,
            UUID order,
            UUID orderItem,
            UUID location) {}
}

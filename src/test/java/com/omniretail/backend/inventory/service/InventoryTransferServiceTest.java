package com.omniretail.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.BranchStatus;
import com.omniretail.backend.administration.entity.BranchType;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.ecommerce.entity.InventoryReservation;
import com.omniretail.backend.ecommerce.entity.InventoryReservationSourceType;
import com.omniretail.backend.ecommerce.repository.InventoryReservationRepository;
import com.omniretail.backend.inventory.dto.ApproveInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.CancelInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.CreateInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.InventoryTransferResponse;
import com.omniretail.backend.inventory.dto.RejectInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.ReserveInventoryCommand;
import com.omniretail.backend.inventory.entity.InventoryTransfer;
import com.omniretail.backend.inventory.entity.InventoryTransferItem;
import com.omniretail.backend.inventory.entity.InventoryTransferReason;
import com.omniretail.backend.inventory.entity.InventoryTransferRequest;
import com.omniretail.backend.inventory.entity.InventoryTransferRequestStatus;
import com.omniretail.backend.inventory.entity.InventoryTransferStatus;
import com.omniretail.backend.inventory.repository.InventoryTransferItemRepository;
import com.omniretail.backend.inventory.repository.InventoryTransferRepository;
import com.omniretail.backend.inventory.repository.InventoryTransferRequestRepository;
import com.omniretail.backend.pos.service.DocumentCounterService;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryTransferServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID REQUESTING_BRANCH_ID = UUID.randomUUID();
    private static final UUID SOURCE_BRANCH_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID TRANSFER_ID = UUID.randomUUID();
    private static final UUID ITEM_ID = UUID.randomUUID();

    @Mock private CurrentUser currentUser;
    @Mock private TenantCapabilityGuard capabilityGuard;
    @Mock private BranchAccessResolver branchAccessResolver;
    @Mock private BranchRepository branchRepository;
    @Mock private ProductRepository productRepository;
    @Mock private InventoryTransferRequestRepository requestRepository;
    @Mock private InventoryTransferRepository transferRepository;
    @Mock private InventoryTransferItemRepository itemRepository;
    @Mock private InventoryReservationRepository reservationRepository;
    @Mock private InventoryReservationLifecycleService reservationLifecycleService;
    @Mock private DocumentCounterService documentCounterService;

    @InjectMocks private InventoryTransferService service;

    @BeforeEach
    void setUp() {
        AuthenticatedUser actor = new AuthenticatedUser(
                USER_ID, TENANT_ID, UserType.employee, UUID.randomUUID(), SOURCE_BRANCH_ID, UUID.randomUUID());
        given(currentUser.require()).willReturn(actor);
        given(branchAccessResolver.resolve(actor))
                .willReturn(new BranchAccess(true, Set.of()));
        given(branchRepository.findByTenantIdAndId(TENANT_ID, REQUESTING_BRANCH_ID))
                .willReturn(Optional.of(branch(REQUESTING_BRANCH_ID)));
        given(branchRepository.findByTenantIdAndId(TENANT_ID, SOURCE_BRANCH_ID))
                .willReturn(Optional.of(branch(SOURCE_BRANCH_ID)));
        given(productRepository.findByTenantIdAndId(TENANT_ID, PRODUCT_ID))
                .willReturn(Optional.of(product(ProductType.physical, true, false)));
        given(requestRepository.saveAndFlush(any())).willAnswer(invocation -> {
            InventoryTransferRequest request = invocation.getArgument(0);
            assignId(request, REQUEST_ID);
            return request;
        });
        given(transferRepository.saveAndFlush(any())).willAnswer(invocation -> {
            InventoryTransfer transfer = invocation.getArgument(0);
            assignId(transfer, TRANSFER_ID);
            return transfer;
        });
        given(itemRepository.saveAndFlush(any())).willAnswer(invocation -> {
            InventoryTransferItem item = invocation.getArgument(0);
            assignId(item, ITEM_ID);
            return item;
        });
        given(documentCounterService.nextInventoryTransferNumber(TENANT_ID))
                .willReturn("TR-2026-00001");
    }

    @Test
    void createsRequestedTransferWithAuthenticatedTenantAndActor() {
        var response = service.createRequest(createRequest());

        assertThat(response.id()).isEqualTo(REQUEST_ID);
        assertThat(response.persistedStatus()).isEqualTo(InventoryTransferRequestStatus.requested);
        assertThat(response.requestedByUserId()).isEqualTo(USER_ID);
        ArgumentCaptor<InventoryTransferRequest> captor =
                ArgumentCaptor.forClass(InventoryTransferRequest.class);
        verify(requestRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT_ID);
        verify(capabilityGuard).ensureTenantCapability(TENANT_ID, SaasCapability.inventory);
    }

    @Test
    void rejectsEqualSourceAndRequestingBranchesBeforePersistence() {
        CreateInventoryTransferRequest invalid = new CreateInventoryTransferRequest(
                SOURCE_BRANCH_ID,
                SOURCE_BRANCH_ID,
                PRODUCT_ID,
                BigDecimal.ONE,
                InventoryTransferReason.replenishment,
                null);

        assertCode(() -> service.createRequest(invalid), "INVALID_INVENTORY_TRANSFER_REQUEST");
        verifyNoInteractions(requestRepository);
    }

    @Test
    void rejectsKitsAndProductsWithUnsupportedTraceability() {
        given(productRepository.findByTenantIdAndId(TENANT_ID, PRODUCT_ID))
                .willReturn(Optional.of(product(ProductType.kit, false, false)));
        assertCode(() -> service.createRequest(createRequest()), "INVENTORY_TRANSFER_PRODUCT_UNSUPPORTED");

        given(productRepository.findByTenantIdAndId(TENANT_ID, PRODUCT_ID))
                .willReturn(Optional.of(product(ProductType.physical, true, true)));
        assertCode(() -> service.createRequest(createRequest()), "INVENTORY_TRANSFER_TRACEABILITY_UNSUPPORTED");
    }

    @Test
    void createRequiresAccessToRequestingBranch() {
        AuthenticatedUser actor = currentUser.require();
        given(branchAccessResolver.resolve(actor)).willReturn(new BranchAccess(false, Set.of()));

        assertCode(() -> service.createRequest(createRequest()), "BRANCH_ACCESS_DENIED");
        verifyNoInteractions(requestRepository);
    }

    @Test
    void createStopsWhenInventoryCapabilityIsUnavailable() {
        BusinessException denied = BusinessException.forbidden(
                "SAAS_CAPABILITY_REQUIRED", "Capability requerida.");
        org.mockito.Mockito.doThrow(denied)
                .when(capabilityGuard)
                .ensureTenantCapability(TENANT_ID, SaasCapability.inventory);

        assertThatThrownBy(() -> service.createRequest(createRequest())).isSameAs(denied);
        verifyNoInteractions(requestRepository);
    }

    @Test
    void approveCreatesTransferItemAndCanonicalReservationThenApprovesRequest() {
        InventoryTransferRequest request = persistedRequest(InventoryTransferRequestStatus.requested);
        given(requestRepository.findForUpdateByTenantIdAndId(TENANT_ID, REQUEST_ID))
                .willReturn(Optional.of(request));
        given(transferRepository.findByTenantIdAndOperationId(TENANT_ID, "approve-1"))
                .willReturn(Optional.empty());

        InventoryTransferResponse response =
                service.approve(REQUEST_ID, new ApproveInventoryTransferRequest("approve-1", "ok"));

        assertThat(response.number()).isEqualTo("TR-2026-00001");
        assertThat(response.status()).isEqualTo(InventoryTransferStatus.preparing);
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(ITEM_ID);
            assertThat(item.sourceRequestId()).isEqualTo(REQUEST_ID);
        });
        ArgumentCaptor<ReserveInventoryCommand> reservation =
                ArgumentCaptor.forClass(ReserveInventoryCommand.class);
        verify(reservationLifecycleService).reserve(reservation.capture());
        assertThat(reservation.getValue().sourceType())
                .isEqualTo(InventoryReservationSourceType.transfer);
        assertThat(reservation.getValue().sourceId()).isEqualTo(TRANSFER_ID);
        assertThat(reservation.getValue().sourceLineId()).isEqualTo(ITEM_ID);
        assertThat(reservation.getValue().orderId()).isNull();
        assertThat(reservation.getValue().orderItemId()).isNull();
        assertThat(request.getStatus()).isEqualTo(InventoryTransferRequestStatus.approved);
        verify(requestRepository).findForUpdateByTenantIdAndId(TENANT_ID, REQUEST_ID);
    }

    @Test
    void failedReservationLeavesRequestPendingForTransactionalRollback() {
        InventoryTransferRequest request = persistedRequest(InventoryTransferRequestStatus.requested);
        given(requestRepository.findForUpdateByTenantIdAndId(TENANT_ID, REQUEST_ID))
                .willReturn(Optional.of(request));
        given(transferRepository.findByTenantIdAndOperationId(TENANT_ID, "approve-1"))
                .willReturn(Optional.empty());
        willThrow(BusinessException.conflict("INSUFFICIENT_STOCK", "Stock insuficiente."))
                .given(reservationLifecycleService)
                .reserve(any());

        assertCode(
                () -> service.approve(
                        REQUEST_ID, new ApproveInventoryTransferRequest("approve-1", null)),
                "INSUFFICIENT_STOCK");
        assertThat(request.getStatus()).isEqualTo(InventoryTransferRequestStatus.requested);
    }

    @Test
    void sameOperationAndFingerprintReplaysExistingTransfer() throws Exception {
        InventoryTransferRequest request = persistedRequest(InventoryTransferRequestStatus.approved);
        InventoryTransfer transfer = persistedTransfer("approve-1");
        InventoryTransferItem item = persistedItem();
        given(requestRepository.findForUpdateByTenantIdAndId(TENANT_ID, REQUEST_ID))
                .willReturn(Optional.of(request));
        given(transferRepository.findByTenantIdAndOperationId(TENANT_ID, "approve-1"))
                .willReturn(Optional.of(transfer));
        given(itemRepository.findByTenantIdAndSourceRequestId(TENANT_ID, REQUEST_ID))
                .willReturn(Optional.of(item));
        String fingerprint = approvalFingerprint(request, "ok");
        transfer.setOperationFingerprint(fingerprint);

        InventoryTransferResponse response =
                service.approve(REQUEST_ID, new ApproveInventoryTransferRequest("approve-1", "ok"));

        assertThat(response.id()).isEqualTo(TRANSFER_ID);
        verifyNoInteractions(reservationLifecycleService);
    }

    @Test
    void reusedOperationWithDifferentFingerprintConflicts() {
        InventoryTransferRequest request = persistedRequest(InventoryTransferRequestStatus.requested);
        InventoryTransfer transfer = persistedTransfer("approve-1");
        transfer.setOperationFingerprint("different");
        given(requestRepository.findForUpdateByTenantIdAndId(TENANT_ID, REQUEST_ID))
                .willReturn(Optional.of(request));
        given(transferRepository.findByTenantIdAndOperationId(TENANT_ID, "approve-1"))
                .willReturn(Optional.of(transfer));

        assertCode(
                () -> service.approve(
                        REQUEST_ID, new ApproveInventoryTransferRequest("approve-1", "ok")),
                "INVENTORY_TRANSFER_OPERATION_CONFLICT");
    }

    @Test
    void rejectAndCancelRequestOnlyAllowRequestedState() {
        InventoryTransferRequest request = persistedRequest(InventoryTransferRequestStatus.requested);
        given(requestRepository.findForUpdateByTenantIdAndId(TENANT_ID, REQUEST_ID))
                .willReturn(Optional.of(request));

        assertThat(service.reject(REQUEST_ID, new RejectInventoryTransferRequest("sin stock"))
                        .persistedStatus())
                .isEqualTo(InventoryTransferRequestStatus.rejected);
        assertCode(
                () -> service.cancelRequest(
                        REQUEST_ID, new CancelInventoryTransferRequest("ya no se necesita")),
                "INVALID_INVENTORY_TRANSFER_REQUEST_STATE");
    }

    @Test
    void cancellingPreparingTransferReleasesItsMatchingReservation() {
        InventoryTransfer transfer = persistedTransfer("approve-1");
        InventoryTransferItem item = persistedItem();
        InventoryReservation reservation = InventoryReservation.builder()
                .branchId(SOURCE_BRANCH_ID)
                .sourceType(InventoryReservationSourceType.transfer)
                .sourceId(TRANSFER_ID)
                .sourceLineId(ITEM_ID)
                .productId(PRODUCT_ID)
                .quantity(new BigDecimal("2.000"))
                .build();
        reservation.setTenantId(TENANT_ID);
        assignId(reservation, UUID.randomUUID());
        given(transferRepository.findForUpdateByTenantIdAndId(TENANT_ID, TRANSFER_ID))
                .willReturn(Optional.of(transfer));
        given(itemRepository.findByTenantIdAndTransferIdOrderByIdAsc(TENANT_ID, TRANSFER_ID))
                .willReturn(List.of(item));
        given(reservationRepository.findByTenantIdAndSourceTypeAndSourceId(
                        TENANT_ID, InventoryReservationSourceType.transfer, TRANSFER_ID))
                .willReturn(List.of(reservation));

        InventoryTransferResponse response = service.cancelTransfer(
                TRANSFER_ID, new CancelInventoryTransferRequest("cancelada"));

        assertThat(response.status()).isEqualTo(InventoryTransferStatus.cancelled);
        verify(reservationLifecycleService).release(TENANT_ID, reservation.getId());
    }

    @Test
    void cannotCancelTransferAfterItLeavesPreparing() {
        InventoryTransfer transfer = persistedTransfer("approve-1");
        transfer.setStatus(InventoryTransferStatus.inTransit);
        given(transferRepository.findForUpdateByTenantIdAndId(TENANT_ID, TRANSFER_ID))
                .willReturn(Optional.of(transfer));
        given(itemRepository.findByTenantIdAndTransferIdOrderByIdAsc(TENANT_ID, TRANSFER_ID))
                .willReturn(List.of(persistedItem()));

        assertCode(
                () -> service.cancelTransfer(
                        TRANSFER_ID, new CancelInventoryTransferRequest("cancelada")),
                "INVALID_INVENTORY_TRANSFER_STATE");
        verifyNoInteractions(reservationLifecycleService);
    }

    @Test
    void requestListDerivesEffectiveStatusFromAssociatedTransfer() {
        InventoryTransferRequest request = persistedRequest(InventoryTransferRequestStatus.approved);
        InventoryTransfer transfer = persistedTransfer("approve-1");
        transfer.setStatus(InventoryTransferStatus.inTransit);
        given(requestRepository.findPage(
                        eq(TENANT_ID), any(), any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(request), PageRequest.of(0, 20), 1));
        given(itemRepository.findByTenantIdAndSourceRequestIdIn(TENANT_ID, List.of(REQUEST_ID)))
                .willReturn(List.of(persistedItem()));
        given(transferRepository.findByTenantIdAndIdIn(TENANT_ID, List.of(TRANSFER_ID)))
                .willReturn(List.of(transfer));

        var response = service.listRequests(null, null, null, PageRequest.of(0, 20));

        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.persistedStatus()).isEqualTo(InventoryTransferRequestStatus.approved);
            assertThat(item.effectiveStatus().name()).isEqualTo("inTransit");
            assertThat(item.transferId()).isEqualTo(TRANSFER_ID);
        });
    }

    @Test
    void restrictedTransferListIsFilteredInRepositoryByAccessibleBranches() {
        AuthenticatedUser actor = currentUser.require();
        given(branchAccessResolver.resolve(actor))
                .willReturn(new BranchAccess(false, Set.of(SOURCE_BRANCH_ID)));
        given(transferRepository.findPageForBranches(
                        eq(TENANT_ID),
                        eq(Set.of(SOURCE_BRANCH_ID)),
                        any(),
                        any(),
                        any(),
                        any()))
                .willReturn(new PageImpl<>(
                        List.<InventoryTransfer>of(), PageRequest.of(0, 20), 0));

        var response = service.listTransfers(null, null, null, PageRequest.of(0, 20));

        assertThat(response.items()).isEmpty();
        verify(transferRepository).findPageForBranches(
                eq(TENANT_ID),
                eq(Set.of(SOURCE_BRANCH_ID)),
                any(),
                any(),
                any(),
                any());
    }

    @Test
    void transferLookupAndRequestMutationAreTenantScoped() {
        given(requestRepository.findForUpdateByTenantIdAndId(TENANT_ID, REQUEST_ID))
                .willReturn(Optional.empty());
        given(transferRepository.findByTenantIdAndId(TENANT_ID, TRANSFER_ID))
                .willReturn(Optional.empty());

        assertCode(
                () -> service.reject(REQUEST_ID, new RejectInventoryTransferRequest(null)),
                "INVENTORY_TRANSFER_REQUEST_NOT_FOUND");
        assertCode(
                () -> service.getTransfer(TRANSFER_ID),
                "INVENTORY_TRANSFER_NOT_FOUND");
    }

    private static String approvalFingerprint(InventoryTransferRequest request, String reviewNotes)
            throws Exception {
        String value = request.getId()
                + "|approve|"
                + request.getSourceBranchId()
                + "|"
                + request.getRequestingBranchId()
                + "|"
                + request.getProductId()
                + "|"
                + request.getRequestedQuantity().stripTrailingZeros().toPlainString()
                + "|"
                + request.getReason()
                + "|"
                + reviewNotes;
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static CreateInventoryTransferRequest createRequest() {
        return new CreateInventoryTransferRequest(
                REQUESTING_BRANCH_ID,
                SOURCE_BRANCH_ID,
                PRODUCT_ID,
                new BigDecimal("2.000"),
                InventoryTransferReason.replenishment,
                "reposición");
    }

    private static InventoryTransferRequest persistedRequest(InventoryTransferRequestStatus status) {
        InventoryTransferRequest request = InventoryTransferRequest.builder()
                .requestingBranchId(REQUESTING_BRANCH_ID)
                .sourceBranchId(SOURCE_BRANCH_ID)
                .productId(PRODUCT_ID)
                .requestedQuantity(new BigDecimal("2.000"))
                .reason(InventoryTransferReason.replenishment)
                .status(status)
                .requestedByUserId(USER_ID)
                .requestedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        request.setTenantId(TENANT_ID);
        assignId(request, REQUEST_ID);
        return request;
    }

    private static InventoryTransfer persistedTransfer(String operationId) {
        InventoryTransfer transfer = InventoryTransfer.builder()
                .number("TR-2026-00001")
                .sourceBranchId(SOURCE_BRANCH_ID)
                .destinationBranchId(REQUESTING_BRANCH_ID)
                .status(InventoryTransferStatus.preparing)
                .operationId(operationId)
                .operationFingerprint("fingerprint")
                .reason(InventoryTransferReason.replenishment)
                .preparedByUserId(USER_ID)
                .preparedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        transfer.setTenantId(TENANT_ID);
        assignId(transfer, TRANSFER_ID);
        return transfer;
    }

    private static InventoryTransferItem persistedItem() {
        InventoryTransferItem item = InventoryTransferItem.builder()
                .transferId(TRANSFER_ID)
                .productId(PRODUCT_ID)
                .sourceRequestId(REQUEST_ID)
                .requestedQuantity(new BigDecimal("2.000"))
                .build();
        item.setTenantId(TENANT_ID);
        assignId(item, ITEM_ID);
        return item;
    }

    private static Branch branch(UUID id) {
        Branch branch = Branch.builder()
                .code(id.toString().substring(0, 8))
                .name("Branch")
                .type(BranchType.store)
                .status(BranchStatus.active)
                .build();
        branch.setTenantId(TENANT_ID);
        assignId(branch, id);
        return branch;
    }

    private static Product product(ProductType type, boolean trackingStock, boolean trackingLot) {
        Product product = Product.builder()
                .sku("SKU")
                .name("Product")
                .productType(type)
                .categoryId(UUID.randomUUID())
                .baseUnitId(UUID.randomUUID())
                .trackingStock(trackingStock)
                .trackingLot(trackingLot)
                .trackingSerial(false)
                .trackingExpiration(false)
                .build();
        product.setTenantId(TENANT_ID);
        assignId(product, PRODUCT_ID);
        return product;
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(code));
    }

    private static void assignId(Object entity, UUID id) {
        ReflectionTestUtils.setField(entity, "id", id);
    }
}

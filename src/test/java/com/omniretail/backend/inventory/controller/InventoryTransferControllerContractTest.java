package com.omniretail.backend.inventory.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.inventory.dto.ApproveInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.CancelInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.CreateInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.InventoryTransferItemResponse;
import com.omniretail.backend.inventory.dto.InventoryTransferReceiptItemResponse;
import com.omniretail.backend.inventory.dto.InventoryTransferReceiptResponse;
import com.omniretail.backend.inventory.dto.InventoryTransferRequestEffectiveStatus;
import com.omniretail.backend.inventory.dto.InventoryTransferRequestResponse;
import com.omniretail.backend.inventory.dto.InventoryTransferResponse;
import com.omniretail.backend.inventory.dto.ReceiveInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.RejectInventoryTransferRequest;
import com.omniretail.backend.inventory.entity.InventoryTransferReason;
import com.omniretail.backend.inventory.entity.InventoryTransferRequestStatus;
import com.omniretail.backend.inventory.entity.InventoryTransferStatus;
import com.omniretail.backend.inventory.service.InventoryTransferService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.GlobalExceptionHandler;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InventoryTransferControllerContractTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-06T12:00:00Z");

    private InventoryTransferService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(InventoryTransferService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                        new InventoryTransferRequestController(service),
                        new InventoryTransferController(service))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createsTransferRequestWithBoundBodyAndCreatedResponse() throws Exception {
        Ids ids = new Ids();
        CreateInventoryTransferRequest request = new CreateInventoryTransferRequest(
                ids.requestingBranchId,
                ids.sourceBranchId,
                ids.productId,
                new BigDecimal("2.500"),
                InventoryTransferReason.replenishment,
                "Reposicion semanal");
        when(service.createRequest(request)).thenReturn(requestResponse(ids, null));

        mvc.perform(post("/inventory/transfer-requests")
                        .contentType(APPLICATION_JSON)
                        .content(createJson(ids, "2.500", "replenishment", "Reposicion semanal")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ids.requestId.toString()))
                .andExpect(jsonPath("$.requestingBranchId").value(ids.requestingBranchId.toString()))
                .andExpect(jsonPath("$.sourceBranchId").value(ids.sourceBranchId.toString()))
                .andExpect(jsonPath("$.productId").value(ids.productId.toString()))
                .andExpect(jsonPath("$.requestedQuantity").value(2.5))
                .andExpect(jsonPath("$.reason").value("replenishment"))
                .andExpect(jsonPath("$.persistedStatus").value("requested"))
                .andExpect(jsonPath("$.effectiveStatus").value("requested"));

        verify(service).createRequest(request);
    }

    @Test
    void listsTransferRequestsWithFiltersAndPageContract() throws Exception {
        Ids ids = new Ids();
        when(service.listRequests(
                        eq(ids.requestingBranchId),
                        eq(ids.sourceBranchId),
                        eq(InventoryTransferRequestStatus.requested),
                        any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(requestResponse(ids, ids.transferId)), 2, 25, 26, 2));

        mvc.perform(get("/inventory/transfer-requests")
                        .param("requestingBranchId", ids.requestingBranchId.toString())
                        .param("sourceBranchId", ids.sourceBranchId.toString())
                        .param("status", "requested")
                        .param("page", "1")
                        .param("size", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(ids.requestId.toString()))
                .andExpect(jsonPath("$.items[0].transferId").value(ids.transferId.toString()))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.pageSize").value(25))
                .andExpect(jsonPath("$.totalItems").value(26))
                .andExpect(jsonPath("$.totalPages").value(2));

        verify(service).listRequests(
                eq(ids.requestingBranchId),
                eq(ids.sourceBranchId),
                eq(InventoryTransferRequestStatus.requested),
                any(Pageable.class));
    }

    @Test
    void approvesRejectsAndCancelsRequestsUsingTheirActualBodyContracts() throws Exception {
        Ids ids = new Ids();
        InventoryTransferResponse transfer = transferResponse(ids, InventoryTransferStatus.preparing);
        when(service.approve(eq(ids.requestId), eq(new ApproveInventoryTransferRequest("approve-1", "ok"))))
                .thenReturn(transfer);
        when(service.reject(ids.requestId, null)).thenReturn(requestResponse(ids, null));
        when(service.cancelRequest(ids.requestId, null)).thenReturn(requestResponse(ids, null));

        mvc.perform(post("/inventory/transfer-requests/{id}/approve", ids.requestId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"operationId\":\"approve-1\",\"reviewNotes\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ids.transferId.toString()))
                .andExpect(jsonPath("$.number").value("TR-2026-00001"))
                .andExpect(jsonPath("$.status").value("preparing"));
        mvc.perform(post("/inventory/transfer-requests/{id}/reject", ids.requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.persistedStatus").value("requested"));
        mvc.perform(post("/inventory/transfer-requests/{id}/cancel", ids.requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveStatus").value("requested"));

        verify(service).approve(ids.requestId, new ApproveInventoryTransferRequest("approve-1", "ok"));
        verify(service).reject(ids.requestId, null);
        verify(service).cancelRequest(ids.requestId, null);
    }

    @Test
    void listsDetailsAndCancelsTransfersWithBoundFiltersAndOptionalBody() throws Exception {
        Ids ids = new Ids();
        InventoryTransferResponse transfer = transferResponse(ids, InventoryTransferStatus.preparing);
        when(service.listTransfers(
                        eq(ids.sourceBranchId),
                        eq(ids.destinationBranchId),
                        eq(InventoryTransferStatus.preparing),
                        any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(transfer), 1, 20, 1, 1));
        when(service.getTransfer(ids.transferId)).thenReturn(transfer);
        when(service.cancelTransfer(ids.transferId, null)).thenReturn(transfer);

        mvc.perform(get("/inventory/transfers")
                        .param("sourceBranchId", ids.sourceBranchId.toString())
                        .param("destinationBranchId", ids.destinationBranchId.toString())
                        .param("status", "preparing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(ids.transferId.toString()))
                .andExpect(jsonPath("$.items[0].sourceBranchId").value(ids.sourceBranchId.toString()))
                .andExpect(jsonPath("$.items[0].destinationBranchId").value(ids.destinationBranchId.toString()))
                .andExpect(jsonPath("$.items[0].items[0].id").value(ids.itemId.toString()));
        mvc.perform(get("/inventory/transfers/{id}", ids.transferId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value("TR-2026-00001"))
                .andExpect(jsonPath("$.reason").value("replenishment"));
        mvc.perform(post("/inventory/transfers/{id}/cancel", ids.transferId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("preparing"));

        verify(service).listTransfers(
                eq(ids.sourceBranchId),
                eq(ids.destinationBranchId),
                eq(InventoryTransferStatus.preparing),
                any(Pageable.class));
        verify(service).getTransfer(ids.transferId);
        verify(service).cancelTransfer(ids.transferId, null);
    }

    @Test
    void receivesTransferWithBoundReceiptAndResponseContract() throws Exception {
        Ids ids = new Ids();
        ReceiveInventoryTransferRequest request = new ReceiveInventoryTransferRequest(
                "receipt-1", ids.locationId, List.of(new com.omniretail.backend.inventory.dto.ReceiveInventoryTransferItemRequest(
                        ids.itemId, new BigDecimal("2.500"))));
        when(service.receiveTransfer(ids.transferId, request)).thenReturn(receiptResponse(ids));

        mvc.perform(post("/inventory/transfers/{id}/receipts", ids.transferId)
                        .contentType(APPLICATION_JSON)
                        .content(receiptJson(ids, "receipt-1", "2.500")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ids.receiptId.toString()))
                .andExpect(jsonPath("$.transferId").value(ids.transferId.toString()))
                .andExpect(jsonPath("$.confirmationId").value("receipt-1"))
                .andExpect(jsonPath("$.destinationBranchId").value(ids.destinationBranchId.toString()))
                .andExpect(jsonPath("$.destinationLocationId").value(ids.locationId.toString()))
                .andExpect(jsonPath("$.items[0].transferItemId").value(ids.itemId.toString()))
                .andExpect(jsonPath("$.transferStatus").value("received"))
                .andExpect(jsonPath("$.idempotent").value(false));

        verify(service).receiveTransfer(ids.transferId, request);
    }

    @Test
    void rejectsInvalidCreateBodiesBeforeCallingService() throws Exception {
        Ids ids = new Ids();
        String longNotes = "x".repeat(501);

        mvc.perform(post("/inventory/transfer-requests"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/inventory/transfer-requests").contentType(APPLICATION_JSON)
                        .content("{\"sourceBranchId\":\"" + ids.sourceBranchId + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/inventory/transfer-requests").contentType(APPLICATION_JSON)
                        .content(createJson(ids, "0", "replenishment", "ok")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/inventory/transfer-requests").contentType(APPLICATION_JSON)
                        .content(createJson(ids, "1.0001", "replenishment", "ok")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/inventory/transfer-requests").contentType(APPLICATION_JSON)
                        .content(createJson(ids, "1.000", null, "ok")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/inventory/transfer-requests").contentType(APPLICATION_JSON)
                        .content(createJson(ids, "1.000", "replenishment", longNotes)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void rejectsInvalidApprovalAndReceiptBodiesBeforeCallingService() throws Exception {
        Ids ids = new Ids();
        mvc.perform(post("/inventory/transfer-requests/{id}/approve", ids.requestId)
                        .contentType(APPLICATION_JSON).content("{\"operationId\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/inventory/transfer-requests/{id}/approve", ids.requestId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"operationId\":\"" + "x".repeat(129) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/inventory/transfers/{id}/receipts", ids.transferId)
                        .contentType(APPLICATION_JSON).content("{\"confirmationId\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/inventory/transfers/{id}/receipts", ids.transferId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"confirmationId\":\"r\",\"items\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/inventory/transfers/{id}/receipts", ids.transferId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"confirmationId\":\"r\",\"destinationLocationId\":\"" + ids.locationId
                                + "\",\"items\":[{\"receivedQuantity\":0}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/inventory/transfers/{id}/receipts", ids.transferId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"confirmationId\":\"r\",\"destinationLocationId\":\"" + ids.locationId
                                + "\",\"items\":[{\"itemId\":\"" + ids.itemId
                                + "\",\"receivedQuantity\":1,\"receivedSelections\":[{\"quantity\":0}]}]}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void rejectsMalformedPathAndFilterValuesBeforeCallingService() throws Exception {
        mvc.perform(get("/inventory/transfers/not-a-uuid"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/inventory/transfers").param("status", "unknown"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/inventory/transfer-requests").param("sourceBranchId", "not-a-uuid"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    private static InventoryTransferRequestResponse requestResponse(Ids ids, UUID transferId) {
        return new InventoryTransferRequestResponse(
                ids.requestId,
                ids.requestingBranchId,
                ids.sourceBranchId,
                ids.productId,
                new BigDecimal("2.500"),
                InventoryTransferReason.replenishment,
                "Reposicion semanal",
                InventoryTransferRequestStatus.requested,
                transferId == null
                        ? InventoryTransferRequestEffectiveStatus.requested
                        : InventoryTransferRequestEffectiveStatus.approved,
                ids.userId,
                CREATED_AT,
                null,
                null,
                null,
                transferId,
                0L,
                CREATED_AT,
                CREATED_AT);
    }

    private static InventoryTransferResponse transferResponse(Ids ids, InventoryTransferStatus status) {
        return new InventoryTransferResponse(
                ids.transferId,
                "TR-2026-00001",
                ids.sourceBranchId,
                ids.destinationBranchId,
                status,
                InventoryTransferReason.replenishment,
                "Reposicion semanal",
                List.of(new InventoryTransferItemResponse(
                        ids.itemId, ids.productId, ids.requestId,
                        new BigDecimal("2.500"), BigDecimal.ZERO, BigDecimal.ZERO)),
                ids.userId,
                CREATED_AT,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0L,
                CREATED_AT,
                CREATED_AT);
    }

    private static InventoryTransferReceiptResponse receiptResponse(Ids ids) {
        return new InventoryTransferReceiptResponse(
                ids.receiptId,
                ids.transferId,
                "receipt-1",
                ids.destinationBranchId,
                ids.locationId,
                ids.userId,
                CREATED_AT,
                List.of(new InventoryTransferReceiptItemResponse(
                        UUID.randomUUID(), ids.itemId, ids.productId, ids.locationId, new BigDecimal("2.500"))),
                InventoryTransferStatus.received,
                false);
    }

    private static String createJson(Ids ids, String quantity, String reason, String notes) {
        String reasonJson = reason == null ? "null" : "\"" + reason + "\"";
        return """
                {"requestingBranchId":"%s","sourceBranchId":"%s","productId":"%s",
                 "requestedQuantity":%s,"reason":%s,"notes":"%s"}
                """.formatted(ids.requestingBranchId, ids.sourceBranchId, ids.productId, quantity, reasonJson, notes);
    }

    private static String receiptJson(Ids ids, String confirmationId, String quantity) {
        return """
                {"confirmationId":"%s","destinationLocationId":"%s",
                 "items":[{"itemId":"%s","receivedQuantity":%s,"receivedSelections":[]}]}
                """.formatted(confirmationId, ids.locationId, ids.itemId, quantity);
    }

    private static final class Ids {
        private final UUID requestId = UUID.randomUUID();
        private final UUID transferId = UUID.randomUUID();
        private final UUID itemId = UUID.randomUUID();
        private final UUID receiptId = UUID.randomUUID();
        private final UUID requestingBranchId = UUID.randomUUID();
        private final UUID sourceBranchId = UUID.randomUUID();
        private final UUID destinationBranchId = UUID.randomUUID();
        private final UUID productId = UUID.randomUUID();
        private final UUID locationId = UUID.randomUUID();
        private final UUID userId = UUID.randomUUID();
    }
}

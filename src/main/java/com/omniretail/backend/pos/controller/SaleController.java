
package com.omniretail.backend.pos.controller;

import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.pos.dto.CreateSaleRequest;
import com.omniretail.backend.pos.dto.PosSalesHistoryPageResponse;
import com.omniretail.backend.pos.dto.SaleConfirmationResponse;
import com.omniretail.backend.pos.dto.SaleDetailResponse;
import com.omniretail.backend.pos.dto.SaleResponse;
import com.omniretail.backend.pos.dto.VoidSaleRequest;
import com.omniretail.backend.pos.entity.SaleStatus;
import com.omniretail.backend.pos.service.PosSalesHistoryService;
import com.omniretail.backend.pos.service.SaleService;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pos/sales")
@RequiredArgsConstructor
public class SaleController {

    private final SaleService service;
    private final PosSalesHistoryService historyService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("pos.sales.create")
    public SaleConfirmationResponse create(@Valid @RequestBody CreateSaleRequest request) {
        return service.create(request);
    }

    @GetMapping
    @RequirePermission("pos.sales.read")
    public Page<SaleResponse> list(
            @RequestParam UUID branchId,
            @RequestParam(required = false) SaleStatus status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            Pageable pageable) {
        return service.list(branchId, status, from, to, pageable);
    }

    @GetMapping("/history")
    @RequirePermission("pos.sales.read")
    public PosSalesHistoryPageResponse history(
            @RequestParam UUID branchId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) SaleStatus status,
            @RequestParam(required = false) DeliveryMethod deliveryMethod,
            @RequestParam(required = false) OrderStatus operationalStatus,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return historyService.search(
                branchId, search, from, to, status, deliveryMethod, operationalStatus, pageable);
    }

    @GetMapping("/{id}")
    @RequirePermission("pos.sales.read")
    public SaleDetailResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/void")
    @RequirePermission("pos.sales.void")
    public Object voidSale(
            @PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey,
            @Valid @RequestBody(required = false) VoidSaleRequest request) {
        if (idempotencyKey == null && request == null) {
            return service.voidSale(id);
        }
        if (idempotencyKey == null) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "IDEMPOTENCY_KEY_REQUIRED",
                    "El encabezado Idempotency-Key es requerido.");
        }
        if (request == null) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "VOID_REQUEST_REQUIRED",
                    "El motivo de anulacion es requerido.");
        }
        return service.voidSale(id, idempotencyKey, request);
    }
}

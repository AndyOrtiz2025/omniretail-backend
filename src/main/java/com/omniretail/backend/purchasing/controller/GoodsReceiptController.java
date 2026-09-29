package com.omniretail.backend.purchasing.controller;

import com.omniretail.backend.purchasing.dto.CreateGoodsReceiptRequest;
import com.omniretail.backend.purchasing.dto.GoodsReceiptResponse;
import com.omniretail.backend.purchasing.dto.UpdateGoodsReceiptRequest;
import com.omniretail.backend.purchasing.entity.GoodsReceiptStatus;
import com.omniretail.backend.purchasing.service.GoodsReceiptService;
import com.omniretail.backend.shared.dto.PageResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/purchasing/receipts")
@RequiredArgsConstructor
public class GoodsReceiptController {

    private final GoodsReceiptService goodsReceiptService;

    @GetMapping
    public PageResponse<GoodsReceiptResponse> list(
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) UUID purchaseOrderId,
            @RequestParam(required = false) GoodsReceiptStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return goodsReceiptService.list(branchId, purchaseOrderId, status, pageable);
    }

    @GetMapping("/{id}")
    public GoodsReceiptResponse get(@PathVariable UUID id) {
        return goodsReceiptService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GoodsReceiptResponse create(@Valid @RequestBody CreateGoodsReceiptRequest request) {
        return goodsReceiptService.create(request);
    }

    @PutMapping("/{id}")
    public GoodsReceiptResponse update(
            @PathVariable UUID id, @Valid @RequestBody UpdateGoodsReceiptRequest request) {
        return goodsReceiptService.update(id, request);
    }

    @PostMapping("/{id}/confirm")
    public GoodsReceiptResponse confirm(@PathVariable UUID id) {
        return goodsReceiptService.confirm(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        goodsReceiptService.delete(id);
    }
}

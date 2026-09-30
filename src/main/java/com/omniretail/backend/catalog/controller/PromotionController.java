package com.omniretail.backend.catalog.controller;

import com.omniretail.backend.catalog.dto.CreatePromotionRequest;
import com.omniretail.backend.catalog.dto.PromotionResponse;
import com.omniretail.backend.catalog.dto.PromotionSummaryResponse;
import com.omniretail.backend.catalog.service.PromotionService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/catalog/promotions")
@RequiredArgsConstructor
public class PromotionController {

    private final PromotionService promotionService;

    @GetMapping
    @RequirePermission("catalog.promotions.read")
    public PageResponse<PromotionSummaryResponse> list(Pageable pageable) {
        return promotionService.list(pageable);
    }

    @GetMapping("/{id}")
    @RequirePermission("catalog.promotions.read")
    public PromotionResponse get(@PathVariable UUID id) {
        return promotionService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("catalog.promotions.manage")
    public PromotionResponse create(@Valid @RequestBody CreatePromotionRequest request) {
        return promotionService.create(request);
    }

    @PutMapping("/{id}/cancel")
    @RequirePermission("catalog.promotions.manage")
    public PromotionResponse cancel(@PathVariable UUID id) {
        return promotionService.cancel(id);
    }
}

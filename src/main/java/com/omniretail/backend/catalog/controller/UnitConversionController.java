package com.omniretail.backend.catalog.controller;

import com.omniretail.backend.catalog.dto.UnitConversionCreateRequest;
import com.omniretail.backend.catalog.dto.UnitConversionResponse;
import com.omniretail.backend.catalog.dto.UnitConversionUpdateRequest;
import com.omniretail.backend.catalog.service.UnitConversionService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
@RequestMapping("/catalog/unit-conversions")
@RequiredArgsConstructor
public class UnitConversionController {

    private final UnitConversionService unitConversionService;

    @GetMapping
    @RequirePermission("catalog.units.read")
    public PageResponse<UnitConversionResponse> list(
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID fromUnitId,
            @RequestParam(required = false) UUID toUnitId,
            @PageableDefault(
                            size = 20,
                            sort = "createdAt",
                            direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return unitConversionService.list(productId, fromUnitId, toUnitId, pageable);
    }

    @GetMapping("/{id}")
    @RequirePermission("catalog.units.read")
    public UnitConversionResponse getById(@PathVariable UUID id) {
        return unitConversionService.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("catalog.units.manage")
    public UnitConversionResponse create(
            @Valid @RequestBody UnitConversionCreateRequest request) {
        return unitConversionService.create(request);
    }

    @PutMapping("/{id}")
    @RequirePermission("catalog.units.manage")
    public UnitConversionResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UnitConversionUpdateRequest request) {
        return unitConversionService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission("catalog.units.manage")
    public void delete(@PathVariable UUID id) {
        unitConversionService.delete(id);
    }
}

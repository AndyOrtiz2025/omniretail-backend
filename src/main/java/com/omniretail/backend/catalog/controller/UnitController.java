package com.omniretail.backend.catalog.controller;

import com.omniretail.backend.catalog.dto.UnitCreateRequest;
import com.omniretail.backend.catalog.dto.UnitResponse;
import com.omniretail.backend.catalog.dto.UnitUpdateRequest;
import com.omniretail.backend.catalog.entity.UnitStatus;
import com.omniretail.backend.catalog.service.UnitService;
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
@RequestMapping("/catalog/units")
@RequiredArgsConstructor
public class UnitController {

    private final UnitService unitService;

    @GetMapping
    @RequirePermission("catalog.units.read")
    public PageResponse<UnitResponse> list(
            @RequestParam(required = false) UnitStatus status,
            @PageableDefault(
                            size = 20,
                            sort = "name",
                            direction = Sort.Direction.ASC)
                    Pageable pageable) {
        return unitService.list(status, pageable);
    }

    @GetMapping("/{id}")
    @RequirePermission("catalog.units.read")
    public UnitResponse getById(@PathVariable UUID id) {
        return unitService.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("catalog.units.manage")
    public UnitResponse create(@Valid @RequestBody UnitCreateRequest request) {
        return unitService.create(request);
    }

    @PutMapping("/{id}")
    @RequirePermission("catalog.units.manage")
    public UnitResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UnitUpdateRequest request) {
        return unitService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission("catalog.units.manage")
    public void archive(@PathVariable UUID id) {
        unitService.archive(id);
    }
}

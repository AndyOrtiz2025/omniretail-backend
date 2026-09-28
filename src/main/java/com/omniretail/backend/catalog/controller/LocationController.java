package com.omniretail.backend.catalog.controller;

import com.omniretail.backend.catalog.dto.LocationCreateRequest;
import com.omniretail.backend.catalog.dto.LocationResponse;
import com.omniretail.backend.catalog.dto.LocationUpdateRequest;
import com.omniretail.backend.catalog.entity.LocationStatus;
import com.omniretail.backend.catalog.entity.LocationType;
import com.omniretail.backend.catalog.service.LocationService;
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
@RequestMapping("/catalog/locations")
@RequiredArgsConstructor
public class LocationController {

    private final LocationService locationService;

    @GetMapping
    @RequirePermission("catalog.locations.read")
    public PageResponse<LocationResponse> list(
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) UUID parentId,
            @RequestParam(required = false) LocationType type,
            @RequestParam(required = false) LocationStatus status,
            @PageableDefault(
                            size = 20,
                            sort = "code",
                            direction = Sort.Direction.ASC)
                    Pageable pageable) {
        return locationService.list(branchId, parentId, type, status, pageable);
    }

    @GetMapping("/{id}")
    @RequirePermission("catalog.locations.read")
    public LocationResponse getById(@PathVariable UUID id) {
        return locationService.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("catalog.locations.manage")
    public LocationResponse create(@Valid @RequestBody LocationCreateRequest request) {
        return locationService.create(request);
    }

    @PutMapping("/{id}")
    @RequirePermission("catalog.locations.manage")
    public LocationResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody LocationUpdateRequest request) {
        return locationService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission("catalog.locations.manage")
    public void archive(@PathVariable UUID id) {
        locationService.archive(id);
    }
}

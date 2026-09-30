package com.omniretail.backend.catalog.controller;

import com.omniretail.backend.catalog.dto.AttributeDefinitionCreateRequest;
import com.omniretail.backend.catalog.dto.AttributeDefinitionResponse;
import com.omniretail.backend.catalog.dto.AttributeDefinitionUpdateRequest;
import com.omniretail.backend.catalog.service.AttributeDefinitionService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/catalog/attributes")
@RequiredArgsConstructor
public class AttributeDefinitionController {

    private final AttributeDefinitionService definitionService;

    @GetMapping
    @RequirePermission("catalog.attributes.read")
    public PageResponse<AttributeDefinitionResponse> list(Pageable pageable) {
        return definitionService.list(pageable);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("catalog.attributes.manage")
    public AttributeDefinitionResponse create(
            @Valid @RequestBody AttributeDefinitionCreateRequest request) {
        return definitionService.create(request);
    }

    @PutMapping("/{id}")
    @RequirePermission("catalog.attributes.manage")
    public AttributeDefinitionResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody AttributeDefinitionUpdateRequest request) {
        return definitionService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission("catalog.attributes.manage")
    public void archive(@PathVariable UUID id) {
        definitionService.archive(id);
    }
}

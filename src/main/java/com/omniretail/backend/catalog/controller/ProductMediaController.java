package com.omniretail.backend.catalog.controller;

import com.omniretail.backend.catalog.dto.ProductMediaCreateRequest;
import com.omniretail.backend.catalog.dto.ProductMediaResponse;
import com.omniretail.backend.catalog.dto.ProductMediaUpdateRequest;
import com.omniretail.backend.catalog.service.ProductMediaService;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/catalog/products/{productId}/media")
@RequiredArgsConstructor
public class ProductMediaController {
    private final ProductMediaService service;
    @GetMapping @RequirePermission("catalog.products.read")
    public List<ProductMediaResponse> list(@PathVariable UUID productId) { return service.list(productId); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @RequirePermission("catalog.products.update")
    public ProductMediaResponse create(@PathVariable UUID productId,
            @Valid @RequestBody ProductMediaCreateRequest request) { return service.createExternal(productId, request); }
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED) @RequirePermission("catalog.products.update")
    public ProductMediaResponse upload(@PathVariable UUID productId, @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String altText, @RequestParam(required = false) Integer sortOrder,
            @RequestParam(required = false) Boolean primary) {
        return service.upload(productId, file, altText, sortOrder, primary);
    }
    @PutMapping("/{mediaId}") @RequirePermission("catalog.products.update")
    public ProductMediaResponse update(@PathVariable UUID productId, @PathVariable UUID mediaId,
            @Valid @RequestBody ProductMediaUpdateRequest request) { return service.update(productId, mediaId, request); }
    @DeleteMapping("/{mediaId}") @ResponseStatus(HttpStatus.NO_CONTENT) @RequirePermission("catalog.products.update")
    public void delete(@PathVariable UUID productId, @PathVariable UUID mediaId) { service.delete(productId, mediaId); }
    @PutMapping("/{mediaId}/primary") @RequirePermission("catalog.products.update")
    public ProductMediaResponse primary(@PathVariable UUID productId, @PathVariable UUID mediaId) {
        return service.setPrimary(productId, mediaId);
    }
}

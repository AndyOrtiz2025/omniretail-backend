package com.omniretail.backend.catalog.controller;

import com.omniretail.backend.catalog.dto.ProductCreateRequest;
import com.omniretail.backend.catalog.dto.ProductChannel;
import com.omniretail.backend.catalog.dto.ProductDto;
import com.omniretail.backend.catalog.dto.ProductListDto;
import com.omniretail.backend.catalog.dto.ProductPromotionFilter;
import com.omniretail.backend.catalog.dto.ProductUpdateRequest;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.service.ProductService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/catalog/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    @RequirePermission("catalog.products.read")
    @Operation(parameters = {
        @Parameter(
                name = "page",
                in = ParameterIn.QUERY,
                description = "Numero de pagina comenzando en 1",
                schema = @Schema(type = "integer", defaultValue = "1", minimum = "1")),
        @Parameter(
                name = "size",
                in = ParameterIn.QUERY,
                description = "Cantidad de elementos por pagina",
                schema = @Schema(type = "integer", defaultValue = "20", minimum = "1")),
        @Parameter(
                name = "sort",
                in = ParameterIn.QUERY,
                description = "Criterio de ordenamiento en formato campo,direccion",
                example = "sku,asc",
                schema = @Schema(type = "string"))
    })
    public PageResponse<ProductListDto> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(required = false) ProductType productType,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) List<ProductChannel> channels,
            @RequestParam(defaultValue = "all") ProductPromotionFilter promotion,
            @Parameter(hidden = true) Pageable pageable) {
        if ((search == null || search.isBlank())
                && status == null
                && productType == null
                && categoryId == null
                && (channels == null || channels.isEmpty())
                && promotion == ProductPromotionFilter.all) {
            return productService.list(pageable);
        }
        return productService.list(
                search, status, productType, categoryId, channels, promotion, pageable);
    }

    @PostMapping
    @RequirePermission("catalog.products.create")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductDto create(@Valid @RequestBody ProductCreateRequest request) {
        return productService.create(request);
    }

    @GetMapping("/{id}")
    @RequirePermission("catalog.products.read")
    public ProductDto get(@PathVariable UUID id) {
        return productService.get(id);
    }

    @PutMapping("/{id}")
    @RequirePermission("catalog.products.update")
    public ProductDto update(
            @PathVariable UUID id, @Valid @RequestBody ProductUpdateRequest request) {
        return productService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @RequirePermission("catalog.products.update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable UUID id) {
        productService.archive(id);
    }

    @PostMapping("/{id}/restore")
    @RequirePermission("catalog.products.update")
    public ProductDto restore(@PathVariable UUID id) {
        return productService.restore(id);
    }
}

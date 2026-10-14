package com.omniretail.backend.ecommerce.controller;

import com.omniretail.backend.ecommerce.service.CustomerPushDeviceService;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/customer/push-devices")
@RequiredArgsConstructor
public class CustomerPushDeviceController {

    private final CustomerPushDeviceService service;

    public record RegisterDeviceRequest(
            @NotBlank String token,
            @NotBlank String platform
    ) {}

    public record UnregisterDeviceRequest(
            @NotBlank String token
    ) {}

    @PostMapping
    @RequirePermission("customer.account.read")
    public ResponseEntity<Void> register(
            @Valid @RequestBody RegisterDeviceRequest request
    ) {
        service.register(request.token(), request.platform());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    @RequirePermission("customer.account.read")
    public ResponseEntity<Void> unregister(
            @Valid @RequestBody UnregisterDeviceRequest request
    ) {
        service.unregister(request.token());
        return ResponseEntity.noContent().build();
    }
}

package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.EcommerceConfigResponse;
import com.omniretail.backend.administration.dto.SaveEcommerceConfigRequest;
import com.omniretail.backend.administration.service.EcommerceConfigService;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/administration/ecommerce-config")
@RequiredArgsConstructor
public class EcommerceConfigController {

    private final EcommerceConfigService ecommerceConfigService;

    @RequirePermission("admin.ecommerce_config.manage")
    @GetMapping
    public EcommerceConfigResponse get() {
        return ecommerceConfigService.getConfig();
    }

    @RequirePermission("admin.ecommerce_config.manage")
    @PutMapping
    public EcommerceConfigResponse save(@Valid @RequestBody SaveEcommerceConfigRequest request) {
        return ecommerceConfigService.saveConfig(request);
    }
}

package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.BusinessConfigResponse;
import com.omniretail.backend.administration.dto.SaveBusinessConfigRequest;
import com.omniretail.backend.administration.service.BusinessConfigService;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/administration/business-config")
@RequiredArgsConstructor
public class BusinessConfigController {

    private final BusinessConfigService businessConfigService;

    // Sin permiso: POS, inventario, recepcion y catalogo leen esta configuracion (igual que el frontend).
    @GetMapping
    public BusinessConfigResponse get() {
        return businessConfigService.getConfig();
    }

    @RequirePermission("admin.business_config.manage")
    @PutMapping
    public BusinessConfigResponse save(@Valid @RequestBody SaveBusinessConfigRequest request) {
        return businessConfigService.saveConfig(request);
    }
}

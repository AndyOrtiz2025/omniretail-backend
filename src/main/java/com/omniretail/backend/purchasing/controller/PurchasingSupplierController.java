package com.omniretail.backend.purchasing.controller;

import com.omniretail.backend.purchasing.dto.PurchasingSupplierResponse;
import com.omniretail.backend.purchasing.service.PurchasingSupplierService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/purchasing/suppliers")
@RequiredArgsConstructor
public class PurchasingSupplierController {

    private final PurchasingSupplierService purchasingSupplierService;

    @GetMapping("/active")
    public List<PurchasingSupplierResponse> listActive() {
        return purchasingSupplierService.listActive();
    }
}

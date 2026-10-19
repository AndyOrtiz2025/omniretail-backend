package com.omniretail.backend.ecommerce.controller;

import com.omniretail.backend.ecommerce.service.StorefrontReceiptService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Descarga pública protegida por el token opaco del pedido. */
@RestController
@RequestMapping("/public/{slug}/receipt")
@RequiredArgsConstructor
public class PublicStorefrontReceiptController {
    private final StorefrontReceiptService receipts;

    @GetMapping("/{token}")
    public ResponseEntity<byte[]> download(@PathVariable String slug, @PathVariable String token) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"comprobante-pedido.pdf\"")
                .contentType(MediaType.APPLICATION_PDF).body(receipts.render(slug, token));
    }
}

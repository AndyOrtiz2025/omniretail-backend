package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.HeroBannerConfigResponse;
import com.omniretail.backend.administration.dto.SaveHeroBannerConfigRequest;
import com.omniretail.backend.administration.service.HeroBannerConfigService;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/administration/hero-banner")
@RequiredArgsConstructor
public class HeroBannerController {

    private final HeroBannerConfigService heroBannerConfigService;

    @RequirePermission("admin.ecommerce_config.manage")
    @GetMapping
    public HeroBannerConfigResponse get() {
        return heroBannerConfigService.getBanner();
    }

    @RequirePermission("admin.ecommerce_config.manage")
    @PutMapping
    public HeroBannerConfigResponse save(@Valid @RequestBody SaveHeroBannerConfigRequest request) {
        return heroBannerConfigService.saveBanner(request);
    }

    @RequirePermission("admin.ecommerce_config.manage")
    @PostMapping(value = "/slides/{index}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public HeroBannerConfigResponse uploadSlideImage(
            @PathVariable int index, @RequestPart("file") MultipartFile file) {
        return heroBannerConfigService.uploadSlideImage(index, file);
    }

    @RequirePermission("admin.ecommerce_config.manage")
    @DeleteMapping("/slides/{index}/image")
    public HeroBannerConfigResponse deleteSlideImage(@PathVariable int index) {
        return heroBannerConfigService.deleteSlideImage(index);
    }
}

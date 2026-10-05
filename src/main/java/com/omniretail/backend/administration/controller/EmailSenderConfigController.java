package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.EmailSenderResponse;
import com.omniretail.backend.administration.dto.SaveEmailSenderRequest;
import com.omniretail.backend.administration.dto.TestEmailSenderRequest;
import com.omniretail.backend.administration.service.EmailSenderConfigService;
import com.omniretail.backend.shared.security.RequirePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Remitente Gmail del tenant. El tenant sale siempre del JWT; nunca de la ruta ni del body. */
@RestController
@RequestMapping("/administration/email-sender")
@RequiredArgsConstructor
public class EmailSenderConfigController {

    private final EmailSenderConfigService emailSenderConfigService;

    @PreAuthorize("@permissionChecker.hasPermission('admin.email_config.read')"
            + " or @permissionChecker.hasPermission('admin.email_config.manage')")
    @GetMapping
    public EmailSenderResponse get() {
        return emailSenderConfigService.getConfig();
    }

    @RequirePermission("admin.email_config.manage")
    @PutMapping
    public EmailSenderResponse save(@RequestBody SaveEmailSenderRequest request) {
        return emailSenderConfigService.saveConfig(request);
    }

    @RequirePermission("admin.email_config.manage")
    @PostMapping("/test")
    public EmailSenderResponse test(@RequestBody TestEmailSenderRequest request) {
        return emailSenderConfigService.sendTest(request);
    }

    @RequirePermission("admin.email_config.manage")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect() {
        emailSenderConfigService.disconnect();
    }
}

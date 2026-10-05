package com.omniretail.backend.shared.notification;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Unico punto que decide el canal: PLATFORM -> remitente de plataforma, TENANT -> cuenta Gmail del tenant. */
@Component
@RequiredArgsConstructor
public class EmailSenderResolver {

    private final PlatformEmailSender platformSender;
    private final TenantEmailSender tenantSender;

    public EmailSender resolveSender(UUID tenantId, EmailPurpose purpose) {
        return purpose.scope() == EmailPurpose.Scope.PLATFORM ? platformSender : tenantSender;
    }
}

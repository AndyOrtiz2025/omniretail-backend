package com.omniretail.backend.shared.notification;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Unico punto que decide el canal: PLATFORM -> remitente de plataforma, TENANT -> cuenta Gmail del tenant.
 * TENANT_PREFERRED lo resuelve {@link EmailDeliveryService} (Gmail del negocio verificado y, si no o si
 * falla, la plataforma); aqui su canal por defecto es la plataforma.
 */
@Component
@RequiredArgsConstructor
public class EmailSenderResolver {

    private final PlatformEmailSender platformSender;
    private final TenantEmailSender tenantSender;

    public EmailSenderChannel defaultChannel(EmailPurpose purpose) {
        return purpose.scope() == EmailPurpose.Scope.TENANT ? EmailSenderChannel.TENANT : EmailSenderChannel.PLATFORM;
    }

    public EmailSender resolveSender(UUID tenantId, EmailPurpose purpose) {
        return sender(defaultChannel(purpose));
    }

    public EmailSender sender(EmailSenderChannel channel) {
        return channel == EmailSenderChannel.TENANT ? tenantSender : platformSender;
    }
}

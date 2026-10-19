package com.omniretail.backend.shared.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Solo los propositos comerciales de plataforma pueden carecer de tenant; todo lo demas (y todo correo del
 * tenant) conserva la exigencia, y los correos sin tenant jamas entran en el registro por tenant ni salen por el
 * remitente del negocio.
 */
class EmailMessageTenantlessTest {

    @Test
    void commercialPurposesArePlatformScopedAndMayLackATenant() {
        for (EmailPurpose purpose : List.of(EmailPurpose.CONTACT_TEAM_NOTIFICATION, EmailPurpose.CONTACT_CONFIRMATION)) {
            assertThat(purpose.scope()).isEqualTo(EmailPurpose.Scope.PLATFORM);
            assertThat(purpose.isTenantless()).isTrue();
        }
        EmailMessage message = EmailMessage.platformHtml(
                EmailPurpose.CONTACT_CONFIRMATION, "persona@example.test", "Asunto", "texto", "<p>html</p>");

        assertThat(message.tenantId()).isNull();
    }

    @Test
    void everyOtherPurposeStillRequiresATenant() {
        for (EmailPurpose purpose : EmailPurpose.values()) {
            if (purpose.isTenantless()) {
                continue;
            }
            assertThatThrownBy(() -> new EmailMessage(null, purpose, "a@b.co", "s", "b", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    void securityPurposesKeepTheirPlatformScopeAndAreNotTenantless() {
        assertThat(EmailPurpose.MFA_CODE.isTenantless()).isFalse();
        assertThat(EmailPurpose.PASSWORD_RESET.scope()).isEqualTo(EmailPurpose.Scope.PLATFORM);
        assertThat(EmailPurpose.ORDER_CONFIRMATION.scope()).isEqualTo(EmailPurpose.Scope.TENANT);
    }

    @Test
    void theResolverSendsCommercialMailOnlyThroughThePlatformSender() {
        PlatformEmailSender platform = mock(PlatformEmailSender.class);
        TenantEmailSender tenant = mock(TenantEmailSender.class);
        EmailSenderResolver resolver = new EmailSenderResolver(platform, tenant);

        for (EmailPurpose purpose : List.of(EmailPurpose.CONTACT_TEAM_NOTIFICATION, EmailPurpose.CONTACT_CONFIRMATION)) {
            assertThat(resolver.defaultChannel(purpose)).isEqualTo(EmailSenderChannel.PLATFORM);
            assertThat(resolver.resolveSender(UUID.randomUUID(), purpose)).isSameAs(platform);
        }
        verifyNoInteractions(tenant);
    }

    @Test
    void theTenantDeliveryRecordRefusesATenantlessMessage() {
        // La comprobacion del tenant ocurre antes de tocar cualquier dependencia.
        EmailDeliveryService deliveries = new EmailDeliveryService(null, null, null, null, null, null, List.of());
        EmailMessage message = EmailMessage.platformHtml(
                EmailPurpose.CONTACT_CONFIRMATION, "persona@example.test", "Asunto", "texto", null);

        assertThatThrownBy(() -> deliveries.recordPending(message)).isInstanceOf(IllegalArgumentException.class);
    }
}

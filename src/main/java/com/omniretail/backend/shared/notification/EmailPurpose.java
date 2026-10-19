package com.omniretail.backend.shared.notification;

/**
 * Motivo de un correo. Su {@link Scope} decide con que remitente sale; ningun modulo elige cuenta.
 *
 * <ul>
 *   <li>{@code PLATFORM}: siempre el remitente de plataforma (MAIL_*). Los enlaces de recuperacion, las
 *       invitaciones (permiten fijar la contrasena) y los codigos MFA nunca salen por el Gmail del negocio:
 *       quedarian en su carpeta "Enviados" y quien tenga acceso a ese Gmail podria entrar a cuentas ajenas.</li>
 *   <li>{@code TENANT_PREFERRED}: el Gmail del negocio si esta VERIFIED; si no, o si falla, la plataforma en
 *       el mismo intento.</li>
 *   <li>{@code TENANT}: solo el Gmail del negocio. Nunca caen al remitente de plataforma.</li>
 * </ul>
 */
public enum EmailPurpose {
    // Seguridad: remitente de plataforma.
    PASSWORD_RESET(Scope.PLATFORM),
    EMPLOYEE_INVITATION(Scope.PLATFORM),
    MFA_CODE(Scope.PLATFORM),
    /** Aviso "ya tienes una cuenta" cuando alguien intenta registrarse con un correo ya registrado. */
    ACCOUNT_ALREADY_EXISTS(Scope.PLATFORM),
    /**
     * Solicitudes comerciales (landing MARJYM): no pertenecen a ningun tenant, asi que salen SIEMPRE por la
     * plataforma y su mensaje no lleva tenantId. No pasan por email_delivery (que exige tenant): tienen su propio
     * seguimiento en contact_requests.
     */
    CONTACT_TEAM_NOTIFICATION(Scope.PLATFORM),
    CONTACT_CONFIRMATION(Scope.PLATFORM),
    // Seguridad sin acceso a la cuenta: el Gmail del negocio verificado, con la plataforma de respaldo.
    EMAIL_VERIFICATION(Scope.TENANT_PREFERRED),
    // Operativos: cuenta Gmail del tenant. Nunca caen al remitente de plataforma.
    ORDER_CONFIRMATION(Scope.TENANT),
    ORDER_STATUS_CHANGED(Scope.TENANT),
    DISPATCH_NOTIFICATION(Scope.TENANT),
    PURCHASE_ORDER(Scope.TENANT),
    RECEIPT_CONFIRMATION(Scope.TENANT),
    /** Correo de prueba de la configuracion del remitente (se envia directo, sin cola). */
    SENDER_TEST(Scope.TENANT);

    public enum Scope {
        PLATFORM,
        TENANT_PREFERRED,
        TENANT
    }

    private final Scope scope;

    EmailPurpose(Scope scope) {
        this.scope = scope;
    }

    public Scope scope() {
        return scope;
    }

    /** Propositos de plataforma que no pertenecen a un tenant (solicitudes comerciales). */
    public boolean isTenantless() {
        return this == CONTACT_TEAM_NOTIFICATION || this == CONTACT_CONFIRMATION;
    }
}

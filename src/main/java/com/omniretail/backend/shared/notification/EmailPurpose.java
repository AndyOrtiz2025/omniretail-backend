package com.omniretail.backend.shared.notification;

/**
 * Motivo de un correo. Su {@link Scope} decide con que remitente sale: los de seguridad usan el
 * remitente de plataforma (MAIL_*) y los operativos la cuenta Gmail del tenant. Ningun modulo elige cuenta.
 */
public enum EmailPurpose {
    // Seguridad: remitente de plataforma.
    EMAIL_VERIFICATION(Scope.PLATFORM),
    PASSWORD_RESET(Scope.PLATFORM),
    EMPLOYEE_INVITATION(Scope.PLATFORM),
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
        TENANT
    }

    private final Scope scope;

    EmailPurpose(Scope scope) {
        this.scope = scope;
    }

    public Scope scope() {
        return scope;
    }
}

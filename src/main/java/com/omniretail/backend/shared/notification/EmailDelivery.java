package com.omniretail.backend.shared.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Registro de un envio. Decision sobre el cuerpo: NUNCA se guarda en claro (los correos de seguridad
 * llevan tokens de reset/activacion). Los de proposito TENANT guardan asunto+cuerpo cifrados
 * ({@code encrypted_payload}) para poder reintentar tras un fallo de transporte; los de plataforma y los
 * TENANT_PREFERRED (verificacion de correo) no guardan nada y no se reintentan (el usuario puede pedir otro enlace).
 */
@Entity
@Table(name = "email_delivery")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, updatable = false)
    private EmailPurpose purpose;

    @Column(name = "recipient", nullable = false, updatable = false)
    private String recipient;

    @Column(name = "subject", updatable = false)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private EmailDeliveryStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    /** Cuando el job de reintentos puede volver a tomarlo; nulo = no se reintenta. */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    /** Codigo saneado ({@link EmailDeliveryException}); nunca un mensaje de la libreria de correo. */
    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "encrypted_payload")
    private String encryptedPayload;

    @Column(name = "payload_iv")
    private String payloadIv;

    @Column(name = "encryption_key_version")
    private Integer encryptionKeyVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    /** Por donde salio el correo, o por donde se intento por ultima vez si fallo. */
    @Enumerated(EnumType.STRING)
    @Column(name = "sender_channel")
    private EmailSenderChannel senderChannel;

    /** El Gmail del negocio fallo y el correo se reenvio por plataforma en el mismo intento. */
    @Column(name = "fallback_used", nullable = false)
    private boolean fallbackUsed;

    /** Codigo saneado del fallo del Gmail del negocio que provoco el fallback. */
    @Column(name = "fallback_reason")
    private String fallbackReason;
}

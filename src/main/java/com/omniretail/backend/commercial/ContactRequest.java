package com.omniretail.backend.commercial;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Solicitud comercial. No pertenece a ningun tenant. La fila se inserta con SQL ({@code ON CONFLICT DO NOTHING},
 * ver {@link ContactRequestService}); esta entidad lee la solicitud y actualiza el seguimiento de los correos.
 */
@Entity
@Table(name = "contact_requests")
@Getter
@Setter
@NoArgsConstructor
public class ContactRequest {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false)
    private ContactRequestStatus status;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "business_name", nullable = false)
    private String businessName;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "phone")
    private String phone;

    @Column(name = "industry", nullable = false)
    private String industry;

    @Column(name = "modules", nullable = false)
    private String modules;

    @Column(name = "message")
    private String message;

    @Column(name = "consent_version", nullable = false)
    private String consentVersion;

    @Column(name = "consent_at", nullable = false)
    private Instant consentAt;

    @Column(name = "idempotency_key")
    private UUID idempotencyKey;

    @Column(name = "dedupe_key")
    private String dedupeKey;

    @Column(name = "dedupe_day")
    private LocalDate dedupeDay;

    @Column(name = "ip_hash")
    private String ipHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "team_mail_status", nullable = false)
    private ContactMailStatus teamMailStatus;

    @Column(name = "team_mail_attempts", nullable = false)
    private int teamMailAttempts;

    @Column(name = "team_mail_next_attempt_at")
    private Instant teamMailNextAttemptAt;

    @Column(name = "team_mail_error_code")
    private String teamMailErrorCode;

    @Column(name = "team_mail_sent_at")
    private Instant teamMailSentAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "confirmation_mail_status", nullable = false)
    private ContactMailStatus confirmationMailStatus;

    @Column(name = "confirmation_mail_attempts", nullable = false)
    private int confirmationMailAttempts;

    @Column(name = "confirmation_mail_next_attempt_at")
    private Instant confirmationMailNextAttemptAt;

    @Column(name = "confirmation_mail_error_code")
    private String confirmationMailErrorCode;

    @Column(name = "confirmation_mail_sent_at")
    private Instant confirmationMailSentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "retention_until", nullable = false, updatable = false)
    private Instant retentionUntil;

    @Column(name = "anonymized_at")
    private Instant anonymizedAt;
}

package com.omniretail.backend.shared.security;

import java.time.Instant;
import java.util.UUID;

/** Status usa String para mantener shared independiente de los enums del modulo auth. */
public record EmployeeAuthSummary(UUID userId, String status, boolean mfaEnabled, Instant lastLoginAt) {
}

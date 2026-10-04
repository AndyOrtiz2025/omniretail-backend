package com.omniretail.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Los 8 codigos de recuperacion, en claro una sola vez: no hay forma de volver a consultarlos. */
public record MfaRecoveryCodesResponse(
        @Schema(description = "8 códigos XXXX-XXXX de un solo uso.") List<String> recoveryCodes) {
}

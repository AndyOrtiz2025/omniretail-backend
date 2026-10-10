package com.omniretail.backend.inventory.service;

import com.omniretail.backend.shared.exception.BusinessException;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;

final class InventoryQuantityPrecondition {

    private InventoryQuantityPrecondition() {}

    static void requireExpectedQuantity(BigDecimal expected, BigDecimal current) {
        if (expected == null) {
            return;
        }
        BigDecimal normalized = expected.stripTrailingZeros();
        int fractionDigits = Math.max(normalized.scale(), 0);
        int integerDigits = Math.max(normalized.precision() - normalized.scale(), 0);
        if (expected.signum() < 0 || fractionDigits > 3 || integerDigits > 9) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_EXPECTED_QUANTITY",
                    "La cantidad esperada debe ser no negativa y admitir hasta tres decimales.");
        }
        if (expected.compareTo(current) != 0) {
            throw BusinessException.conflict(
                    "COUNT_SNAPSHOT_STALE",
                    "La existencia fisica cambio desde que se obtuvo el conteo.");
        }
    }
}

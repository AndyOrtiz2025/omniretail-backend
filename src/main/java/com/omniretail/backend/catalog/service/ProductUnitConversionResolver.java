package com.omniretail.backend.catalog.service;

import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.UnitConversion;
import com.omniretail.backend.catalog.repository.UnitConversionRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductUnitConversionResolver {

    private final UnitConversionRepository conversions;

    public BigDecimal toBaseQuantity(UUID tenantId, Product product, BigDecimal commercialQuantity) {
        UUID baseUnitId = product.getBaseUnitId();
        UUID saleUnitId = product.getSaleUnitId() == null ? baseUnitId : product.getSaleUnitId();
        if (baseUnitId == null || saleUnitId == null) {
            throw invalidConversion();
        }

        BigDecimal factor = BigDecimal.ONE;
        if (!saleUnitId.equals(baseUnitId)) {
            factor = conversions
                    .findByTenantIdAndProductIdAndFromUnitIdAndToUnitId(
                            tenantId, product.getId(), saleUnitId, baseUnitId)
                    .or(() -> conversions.findByTenantIdAndFromUnitIdAndToUnitIdAndProductIdIsNull(
                            tenantId, saleUnitId, baseUnitId))
                    .map(UnitConversion::getFactor)
                    .filter(value -> value.signum() > 0)
                    .orElseThrow(ProductUnitConversionResolver::invalidConversion);
        }

        try {
            BigDecimal result = commercialQuantity.multiply(factor).setScale(3, RoundingMode.UNNECESSARY);
            if (Boolean.TRUE.equals(product.getTrackingStock())
                    && result.stripTrailingZeros().scale() > 0) {
                throw new ArithmeticException("Stock quantity must be an integer");
            }
            return result;
        } catch (ArithmeticException exception) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_INVENTORY_QUANTITY",
                    "La cantidad convertida no cumple la precisión requerida por inventario.");
        }
    }

    private static BusinessException invalidConversion() {
        return new BusinessException(
                HttpStatus.BAD_REQUEST,
                "UNIT_CONVERSION_REQUIRED",
                "No existe una conversión válida entre la unidad de venta y la unidad base.");
    }
}

package com.omniretail.backend.pos.dto;

import com.omniretail.backend.pos.entity.Sale;
import com.omniretail.backend.pos.entity.SaleDocumentType;

public record SaleDocumentResponse(
        SaleDocumentType type,
        String taxId,
        String legalName,
        String fiscalAddress) {

    public static SaleDocumentResponse from(Sale sale) {
        SaleDocumentType type = sale.getDocumentType() == null
                ? SaleDocumentType.ticket
                : sale.getDocumentType();
        return new SaleDocumentResponse(
                type,
                sale.getDocumentTaxId(),
                sale.getDocumentLegalName(),
                sale.getDocumentFiscalAddress());
    }
}

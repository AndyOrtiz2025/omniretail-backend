package com.omniretail.backend.purchasing.service;

import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.Supplier;
import com.omniretail.backend.purchasing.entity.PurchaseOrder;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.util.HtmlUtils;

/**
 * Plantilla del correo de aprobación de una orden de compra: texto plano (fallback) y HTML compatible con
 * clientes de correo (tablas simples y CSS en línea, sin JS). Es un RESUMEN: el detalle de productos va en el PDF
 * adjunto, porque una orden puede tener cientos de líneas. Todo dato variable se escapa antes de entrar al HTML.
 */
final class PurchaseOrderEmailTemplate {

    record Content(String subject, String plainText, String html) {}

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Guatemala");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String NAVY = "#143158";
    private static final String BLUE = "#2e69a0";
    private static final String INK = "#212529";
    private static final String MUTED = "#5f6c7d";
    private static final String BORDER = "#d3dceb";
    private static final String PALE = "#f1f5fa";
    private static final String DETAIL_NOTICE =
            "El detalle completo de productos, cantidades, precios y condiciones se encuentra en el PDF adjunto.";

    private PurchaseOrderEmailTemplate() {
    }

    static Content render(
            String businessName, PurchaseOrder order, Supplier supplier, Branch branch, int lineCount) {
        String business = hasText(businessName) ? businessName.trim() : "OmniRetail";
        String supplierName = hasText(supplier.getName()) ? supplier.getName().trim() : "Proveedor";
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"Numero de orden", order.getNumber()});
        if (order.getCreatedAt() != null) {
            rows.add(new String[] {"Fecha de la orden", DATE.format(order.getCreatedAt().atZone(BUSINESS_ZONE))});
        }
        if (order.getExpectedDate() != null) {
            rows.add(new String[] {"Fecha esperada de entrega", DATE.format(order.getExpectedDate())});
        }
        if (branch != null && hasText(branch.getName())) {
            rows.add(new String[] {"Sucursal de recepcion", branch.getName()});
            if (hasText(branch.getAddress())) {
                rows.add(new String[] {"Direccion de entrega", branch.getAddress()});
            }
        }
        rows.add(new String[] {"Lineas de producto", String.valueOf(lineCount)});
        rows.add(new String[] {"Total", money(order.getTotal())});

        String subject = "Orden de compra " + order.getNumber() + " aprobada";
        return new Content(subject, plain(business, supplierName, order, rows), html(business, supplierName, order, rows));
    }

    private static String plain(String business, String supplierName, PurchaseOrder order, List<String[]> rows) {
        StringBuilder text = new StringBuilder();
        text.append("Estimado(a) ").append(supplierName).append(":\n\n");
        text.append("Le notificamos que la orden de compra ").append(order.getNumber())
                .append(" ha sido aprobada.\n\n");
        text.append("Resumen de la orden:\n");
        for (String[] row : rows) {
            text.append("- ").append(row[0]).append(": ").append(row[1]).append("\n");
        }
        text.append("\n").append(DETAIL_NOTICE).append("\n\n");
        text.append("Saludos cordiales.\n").append(business);
        return text.toString();
    }

    private static String html(String business, String supplierName, PurchaseOrder order, List<String[]> rows) {
        StringBuilder summary = new StringBuilder();
        for (String[] row : rows) {
            boolean total = "Total".equals(row[0]);
            summary.append("<tr>")
                    .append("<td style=\"padding:8px 12px;border-bottom:1px solid ").append(BORDER)
                    .append(";font-size:12px;color:").append(MUTED).append(";width:42%;\">")
                    .append(esc(row[0])).append("</td>")
                    .append("<td style=\"padding:8px 12px;border-bottom:1px solid ").append(BORDER)
                    .append(";font-size:13px;color:").append(INK)
                    .append(total ? ";font-weight:bold;" : ";").append("\">")
                    .append(esc(row[1])).append("</td></tr>");
        }
        return "<!DOCTYPE html><html lang=\"es\"><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Orden de compra " + esc(order.getNumber()) + "</title></head>"
                + "<body style=\"margin:0;padding:0;background:#eef2f7;font-family:Arial,Helvetica,sans-serif;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"background:#eef2f7;\"><tr><td align=\"center\" style=\"padding:24px 12px;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"max-width:600px;background:#ffffff;border:1px solid " + BORDER + ";border-radius:6px;\">"
                + "<tr><td style=\"background:" + NAVY + ";padding:20px 24px;border-radius:6px 6px 0 0;\">"
                + "<div style=\"font-size:11px;letter-spacing:1px;color:#c9d7ea;text-transform:uppercase;\">"
                + esc(business) + "</div>"
                + "<div style=\"font-size:20px;font-weight:bold;color:#ffffff;margin-top:6px;\">"
                + "Orden de compra aprobada</div></td></tr>"
                + "<tr><td style=\"padding:24px;color:" + INK + ";font-size:14px;line-height:1.5;\">"
                + "<p style=\"margin:0 0 12px 0;\">Estimado(a) <strong>" + esc(supplierName) + "</strong>:</p>"
                + "<p style=\"margin:0 0 18px 0;\">Le notificamos que la orden de compra <strong>"
                + esc(order.getNumber()) + "</strong> ha sido aprobada.</p>"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"border:1px solid " + BORDER + ";border-radius:4px;background:" + PALE + ";\">"
                + summary + "</table>"
                + "<p style=\"margin:18px 0 0 0;padding:12px;border-left:3px solid " + BLUE + ";background:" + PALE
                + ";font-size:13px;\">" + esc(DETAIL_NOTICE) + "</p>"
                + "<p style=\"margin:20px 0 0 0;\">Saludos cordiales.<br><strong>" + esc(business)
                + "</strong></p></td></tr>"
                + "<tr><td style=\"padding:14px 24px;border-top:1px solid " + BORDER + ";font-size:11px;color:"
                + MUTED + ";\">Mensaje generado automaticamente por OmniRetail.</td></tr>"
                + "</table></td></tr></table></body></html>";
    }

    private static String money(BigDecimal value) {
        BigDecimal amount = value == null ? BigDecimal.ZERO : value;
        return "Q" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String esc(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value, "UTF-8");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

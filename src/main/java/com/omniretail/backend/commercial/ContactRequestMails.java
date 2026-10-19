package com.omniretail.backend.commercial;

import com.omniretail.backend.shared.notification.EmailLayout;
import com.omniretail.backend.shared.notification.EmailMessage;
import com.omniretail.backend.shared.notification.EmailPurpose;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Contenido de los dos correos de una solicitud. Todo texto variable pasa por {@link EmailLayout}, que lo escapa
 * para HTML. Los asuntos son fijos y la confirmacion no repite comentarios ni metadatos internos. El campo
 * {@code website} de entrada es el honeypot anti-spam: no forma parte de la entidad persistida ni de estos correos.
 */
final class ContactRequestMails {

    private static final String BRAND = "MARJYM";
    private static final String CONFIRMATION_SUBJECT = "Recibimos tu solicitud | MARJYM";
    private static final String TEAM_SUBJECT = "Nueva solicitud comercial | MARJYM";
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private static final Map<String, String> INDUSTRY_NAMES = Map.of(
            "retail", "Venta minorista",
            "wholesale", "Venta mayorista",
            "hardware", "Ferretería",
            "pharmacy", "Farmacia",
            "grocery", "Supermercado o abarrotes",
            "services", "Servicios",
            "other", "Otro");
    private static final Map<String, String> MODULE_NAMES = Map.ofEntries(
            Map.entry("inventory", "Inventario"),
            Map.entry("purchasing", "Compras"),
            Map.entry("receiving", "Recepciones"),
            Map.entry("pos", "Punto de venta (POS)"),
            Map.entry("ecommerce", "Comercio electrónico"),
            Map.entry("delivery", "Entregas"),
            Map.entry("reports.advanced", "Reportes avanzados"),
            Map.entry("traceability.lots", "Trazabilidad por lotes"),
            Map.entry("traceability.expiration", "Control de vencimientos"),
            Map.entry("traceability.serials", "Trazabilidad por series"),
            Map.entry("catalog.kits", "Kits de productos"));

    private ContactRequestMails() {
    }

    static EmailMessage confirmation(ContactRequest request) {
        String id = request.getId().toString();
        String intro = "Gracias por tu interés en MARJYM. Confirmamos que recibimos tu solicitud comercial.";
        String next = "El equipo comercial revisará la información y podrá contactarte por correo electrónico "
                + "o teléfono.";
        String body = "Hola " + request.getFullName() + ",\n\n"
                + intro + "\n\n"
                + "Resumen\n"
                + "Negocio: " + request.getBusinessName() + "\n"
                + "Estado: Recibida\n"
                + "Identificador de solicitud: " + id + "\n\n"
                + "Próximos pasos\n" + next + "\n\n"
                + "Si no realizaste esta solicitud, puedes ignorar este mensaje.\n\n"
                + BRAND;
        String html = EmailLayout.render(BRAND, "Recibimos tu solicitud", List.of(
                EmailLayout.paragraph("Hola " + request.getFullName() + ","),
                EmailLayout.paragraph(intro),
                EmailLayout.sectionTitle("Resumen de la solicitud"),
                EmailLayout.details(List.of(
                        new String[] {"Negocio", request.getBusinessName()},
                        new String[] {"Estado", "Recibida"},
                        new String[] {"Identificador", id})),
                EmailLayout.sectionTitle("Próximos pasos"),
                EmailLayout.notice(next),
                EmailLayout.paragraph("Si no realizaste esta solicitud, puedes ignorar este mensaje.")), BRAND);
        return EmailMessage.platformHtml(
                EmailPurpose.CONTACT_CONFIRMATION, request.getEmail(), CONFIRMATION_SUBJECT, body, html);
    }

    static EmailMessage teamNotification(ContactRequest request, String teamAddress) {
        String modules = readableModules(request.getModules());
        List<String[]> requestRows = List.of(
                new String[] {"Identificador", request.getId().toString()},
                new String[] {"Estado", "Recibida"},
                new String[] {"Fecha de recepción", WHEN.format(request.getCreatedAt())});
        List<String[]> contactRows = new ArrayList<>();
        contactRows.add(new String[] {"Nombre", request.getFullName()});
        contactRows.add(new String[] {"Empresa", request.getBusinessName()});
        contactRows.add(new String[] {"Correo", request.getEmail()});
        addIfPresent(contactRows, "Teléfono", request.getPhone());
        List<String[]> interestRows = List.of(
                new String[] {"Rubro", readableIndustry(request.getIndustry())},
                new String[] {"Módulos seleccionados", modules});
        List<String[]> consentRows = List.<String[]>of(new String[] {
                "Consentimiento", request.getConsentVersion() + " · " + WHEN.format(request.getConsentAt())
        });

        StringBuilder text = new StringBuilder("Nueva solicitud comercial\n\n");
        appendRows(text, requestRows);
        text.append("\nDatos de contacto\n");
        appendRows(text, contactRows);
        text.append("\nInterés comercial\n");
        appendRows(text, interestRows);
        if (hasText(request.getMessage())) {
            text.append("\nComentarios\n").append(request.getMessage()).append('\n');
        }
        text.append("\nConsentimiento\n");
        appendRows(text, consentRows);

        List<EmailLayout.Block> blocks = new ArrayList<>();
        blocks.add(EmailLayout.paragraph("Se recibió una nueva solicitud desde el formulario comercial de MARJYM."));
        blocks.add(EmailLayout.sectionTitle("Solicitud"));
        blocks.add(EmailLayout.details(requestRows));
        blocks.add(EmailLayout.sectionTitle("Datos de contacto"));
        blocks.add(EmailLayout.details(contactRows));
        blocks.add(EmailLayout.sectionTitle("Interés comercial"));
        blocks.add(EmailLayout.details(interestRows));
        if (hasText(request.getMessage())) {
            blocks.add(EmailLayout.sectionTitle("Comentarios"));
            blocks.add(EmailLayout.textPanel(request.getMessage()));
        }
        blocks.add(EmailLayout.sectionTitle("Consentimiento"));
        blocks.add(EmailLayout.details(consentRows));

        String html = EmailLayout.render(BRAND, "Nueva solicitud comercial", blocks, BRAND);
        return EmailMessage.platformHtml(
                EmailPurpose.CONTACT_TEAM_NOTIFICATION, teamAddress, TEAM_SUBJECT, text.toString(), html);
    }

    private static String readableModules(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(ContactRequestMails::hasText)
                .map(module -> MODULE_NAMES.getOrDefault(module, module))
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private static String readableIndustry(String value) {
        return INDUSTRY_NAMES.getOrDefault(value, value);
    }

    private static void addIfPresent(List<String[]> rows, String label, String value) {
        if (hasText(value)) {
            rows.add(new String[] {label, value});
        }
    }

    private static void appendRows(StringBuilder text, List<String[]> rows) {
        for (String[] row : rows) {
            if (row.length >= 2 && hasText(row[1])) {
                text.append(row[0]).append(": ").append(row[1]).append('\n');
            }
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

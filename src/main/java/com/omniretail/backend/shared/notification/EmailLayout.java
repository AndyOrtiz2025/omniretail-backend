package com.omniretail.backend.shared.notification;

import java.util.List;
import java.util.Locale;
import org.springframework.web.util.HtmlUtils;

/**
 * Layout HTML comun de los correos (mismo diseno que la plantilla de ordenes de compra): tablas simples y CSS en
 * linea, sin JS, con ancho maximo de 600px para que se lea bien en el celular. Cada correo solo arma sus
 * {@link Block bloques}; el layout escapa TODO texto variable (encabezado, titulo y bloques), asi ningun modulo
 * puede meter HTML sin escapar.
 */
public final class EmailLayout {

    private static final String NAVY = "#143158";
    private static final String BLUE = "#2e69a0";
    private static final String INK = "#212529";
    private static final String MUTED = "#5f6c7d";
    private static final String BORDER = "#d3dceb";
    private static final String PALE = "#f1f5fa";
    private static final String DEFAULT_EYEBROW = "OmniRetail";
    private static final String DEFAULT_FOOTER = "Mensaje generado automaticamente por OmniRetail.";

    /** Fragmento del cuerpo del correo; se construye solo con las fabricas de esta clase. */
    public static final class Block {

        private final String html;

        private Block(String html) {
            this.html = html;
        }
    }

    private EmailLayout() {
    }

    /**
     * @param eyebrow texto pequeno del encabezado (p. ej. el nombre del negocio); vacio -> "OmniRetail".
     * @param title   titulo del encabezado.
     */
    public static String render(String eyebrow, String title, List<Block> blocks) {
        return render(eyebrow, title, blocks, DEFAULT_FOOTER);
    }

    /** Igual que {@link #render(String, String, List)}, con un pie de marca especifico para el remitente. */
    public static String render(String eyebrow, String title, List<Block> blocks, String footer) {
        String header = hasText(eyebrow) ? eyebrow.trim() : DEFAULT_EYEBROW;
        StringBuilder body = new StringBuilder();
        for (Block block : blocks) {
            body.append(block.html);
        }
        return "<!DOCTYPE html><html lang=\"es\"><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + escape(title) + "</title></head>"
                + "<body style=\"margin:0;padding:0;background:#eef2f7;font-family:Arial,Helvetica,sans-serif;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"background:#eef2f7;\"><tr><td align=\"center\" style=\"padding:24px 12px;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"max-width:600px;background:#ffffff;border:1px solid " + BORDER + ";border-radius:6px;\">"
                + "<tr><td style=\"background:" + NAVY + ";padding:20px 24px;border-radius:6px 6px 0 0;\">"
                + "<div style=\"font-size:11px;letter-spacing:1px;color:#c9d7ea;text-transform:uppercase;\">"
                + escape(header) + "</div>"
                + "<div style=\"font-size:20px;font-weight:bold;color:#ffffff;margin-top:6px;\">"
                + escape(title) + "</div></td></tr>"
                + "<tr><td style=\"padding:24px;color:" + INK + ";font-size:14px;line-height:1.5;\">"
                + body + "</td></tr>"
                + "<tr><td style=\"padding:14px 24px;border-top:1px solid " + BORDER + ";font-size:11px;color:"
                + MUTED + ";\">" + escape(footer) + "</td></tr>"
                + "</table></td></tr></table></body></html>";
    }

    /** Parrafo de texto. */
    public static Block paragraph(String text) {
        return new Block("<p style=\"margin:0 0 14px 0;\">" + escape(text) + "</p>");
    }

    /** Parrafo con una parte en negrita: {@code before + <strong>bold</strong> + after}. */
    public static Block paragraph(String before, String bold, String after) {
        return new Block("<p style=\"margin:0 0 14px 0;\">" + escape(before) + "<strong>" + escape(bold)
                + "</strong>" + escape(after) + "</p>");
    }

    /**
     * Boton con el enlace, y el mismo enlace en texto debajo por si el boton no abre. Solo acepta http(s):
     * un {@code javascript:} u otro esquema lanza {@link IllegalArgumentException}.
     */
    public static Block button(String label, String url) {
        String safeUrl = escape(requireHttpUrl(url));
        return new Block("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"margin:8px 0 18px 0;\"><tr><td style=\"background:" + BLUE + ";border-radius:4px;\">"
                + "<a href=\"" + safeUrl + "\" target=\"_blank\" rel=\"noopener\" "
                + "style=\"display:inline-block;padding:12px 24px;font-size:15px;font-weight:bold;color:#ffffff;"
                + "text-decoration:none;border-radius:4px;\">" + escape(label) + "</a></td></tr></table>"
                + "<p style=\"margin:0 0 6px 0;font-size:12px;color:" + MUTED + ";\">"
                + "Si el botón no abre, copia y pega este enlace en tu navegador:</p>"
                + "<p style=\"margin:0 0 18px 0;font-size:12px;word-break:break-all;\">"
                + "<a href=\"" + safeUrl + "\" target=\"_blank\" rel=\"noopener\" style=\"color:" + BLUE + ";\">"
                + safeUrl + "</a></p>");
    }

    /** Codigo de verificacion grande y espaciado, facil de leer y copiar. */
    public static Block code(String value) {
        return new Block("<div style=\"margin:8px 0 18px 0;padding:16px;text-align:center;background:" + PALE
                + ";border:1px solid " + BORDER + ";border-radius:4px;font-family:'Courier New',Courier,monospace;"
                + "font-size:32px;font-weight:bold;letter-spacing:8px;color:" + NAVY + ";\">"
                + escape(value) + "</div>");
    }

    /** Aviso destacado con borde azul (vencimiento, advertencias). */
    public static Block notice(String text) {
        return new Block("<p style=\"margin:0 0 14px 0;padding:12px;border-left:3px solid " + BLUE
                + ";background:" + PALE + ";font-size:13px;\">" + escape(text) + "</p>");
    }

    /** Titulo de seccion compacto para separar grupos de informacion. */
    public static Block sectionTitle(String text) {
        return new Block("<h2 style=\"margin:22px 0 10px 0;font-size:15px;line-height:1.3;color:" + NAVY
                + ";\">" + escape(text) + "</h2>");
    }

    /** Tabla de etiqueta y valor. Las filas sin valor se omiten para no inventar datos ausentes. */
    public static Block details(List<String[]> rows) {
        StringBuilder html = new StringBuilder("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" "
                + "cellspacing=\"0\" style=\"margin:0 0 16px 0;border:1px solid " + BORDER
                + ";border-radius:4px;border-collapse:separate;\">");
        boolean included = false;
        for (String[] row : rows) {
            if (row == null || row.length < 2 || !hasText(row[1])) {
                continue;
            }
            included = true;
            html.append("<tr><td style=\"width:34%;padding:9px 12px;border-bottom:1px solid ")
                    .append(BORDER)
                    .append(";font-size:12px;font-weight:bold;color:")
                    .append(MUTED)
                    .append(";vertical-align:top;\">")
                    .append(escape(row[0]))
                    .append("</td><td style=\"padding:9px 12px;border-bottom:1px solid ")
                    .append(BORDER)
                    .append(";font-size:13px;color:")
                    .append(INK)
                    .append(";word-break:break-word;vertical-align:top;\">")
                    .append(escape(row[1]))
                    .append("</td></tr>");
        }
        if (!included) {
            return new Block("");
        }
        return new Block(html.append("</table>").toString());
    }

    /** Texto libre preservando saltos de linea; siempre se escapa antes de insertarlo. */
    public static Block textPanel(String text) {
        return new Block("<div style=\"margin:0 0 16px 0;padding:12px;background:" + PALE + ";border:1px solid "
                + BORDER + ";border-radius:4px;white-space:pre-wrap;word-break:break-word;font-size:13px;\">"
                + escape(text) + "</div>");
    }

    /** Escapa texto para HTML (contenido y atributos). */
    public static String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value, "UTF-8");
    }

    private static String requireHttpUrl(String url) {
        String trimmed = url == null ? "" : url.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IllegalArgumentException("El enlace del correo debe usar http o https.");
        }
        return trimmed;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

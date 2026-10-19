package com.omniretail.backend.commercial;

import static org.assertj.core.api.Assertions.assertThat;

import com.omniretail.backend.shared.notification.EmailMessage;
import com.omniretail.backend.shared.notification.EmailPurpose;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContactRequestMailsTest {

    private static final UUID REQUEST_ID = UUID.fromString("98f2ed37-5bc2-4fce-876f-d383dc118410");
    private static final String TEAM = "ventas@marjym.test";

    @Test
    void confirmationUsesMarjymBrandAResponsiveSummaryAndPlainTextWithoutInternalData() {
        ContactRequest request = request("+502 5555-1234", "Comentario confidencial que no debe repetirse");

        EmailMessage email = ContactRequestMails.confirmation(request);

        assertThat(email.purpose()).isEqualTo(EmailPurpose.CONTACT_CONFIRMATION);
        assertThat(email.subject()).isEqualTo("Recibimos tu solicitud | MARJYM");
        assertThat(email.to()).isEqualTo("ana@example.test");
        assertThat(email.body()).contains(
                "Hola Ana <script>alert(1)</script> Pérez,",
                "Negocio: Café & Compañía <b>Central</b>",
                "Estado: Recibida",
                "Identificador de solicitud: " + REQUEST_ID,
                "Próximos pasos",
                "correo electrónico o teléfono")
                .doesNotContain("Comentario confidencial", "2026-10", "inventory", "ip_hash");
        assertThat(email.html()).contains(
                "<meta name=\"viewport\"",
                "max-width:600px",
                "background:#143158",
                "Resumen de la solicitud",
                "Próximos pasos",
                "Ana &lt;script&gt;alert(1)&lt;/script&gt; Pérez",
                "Café &amp; Compañía &lt;b&gt;Central&lt;/b&gt;",
                REQUEST_ID.toString())
                .doesNotContain("<script>alert(1)</script>", "<b>Central</b>", "Comentario confidencial", "OmniRetail");
    }

    @Test
    void teamNotificationIncludesAllAvailableDataWithReadableNamesAndSafeUnicode() {
        ContactRequest request = request(
                "+502 5555-1234",
                "Línea uno <img src=x onerror=alert(1)>\nGracias 😀");

        EmailMessage email = ContactRequestMails.teamNotification(request, TEAM);

        assertThat(email.purpose()).isEqualTo(EmailPurpose.CONTACT_TEAM_NOTIFICATION);
        assertThat(email.subject()).isEqualTo("Nueva solicitud comercial | MARJYM");
        assertThat(email.to()).isEqualTo(TEAM);
        assertThat(email.body()).contains(
                REQUEST_ID.toString(),
                "Fecha de recepción: 2026-10-18 16:30 UTC",
                "Nombre: Ana <script>alert(1)</script> Pérez",
                "Empresa: Café & Compañía <b>Central</b>",
                "Correo: ana@example.test",
                "Teléfono: +502 5555-1234",
                "Rubro: Venta minorista",
                "Inventario",
                "Punto de venta (POS)",
                "Compras",
                "Recepciones",
                "Comercio electrónico",
                "Entregas",
                "Reportes avanzados",
                "Trazabilidad por lotes",
                "Control de vencimientos",
                "Trazabilidad por series",
                "Kits de productos",
                "Línea uno <img src=x onerror=alert(1)>",
                "Gracias 😀",
                "Consentimiento: 2026-10 · 2026-10-18 16:29 UTC")
                .doesNotContain(ContactCatalog.MODULES.toArray(String[]::new));
        assertThat(email.html()).contains(
                "Datos de contacto",
                "Interés comercial",
                "Comentarios",
                "Consentimiento",
                "Ana &lt;script&gt;alert(1)&lt;/script&gt; Pérez",
                "Café &amp; Compañía &lt;b&gt;Central&lt;/b&gt;",
                "Línea uno &lt;img src=x onerror=alert(1)&gt;",
                "Gracias 😀",
                "white-space:pre-wrap")
                .doesNotContain(
                        "<script>alert(1)</script>",
                        "<b>Central</b>",
                        "<img src=x",
                        "OmniRetail");
        assertThat(email.html()).doesNotContain(ContactCatalog.MODULES.toArray(String[]::new));
    }

    @Test
    void teamNotificationOmitsOptionalFieldsInsteadOfInventingValues() {
        ContactRequest request = request(null, null);

        EmailMessage email = ContactRequestMails.teamNotification(request, TEAM);

        assertThat(email.body()).doesNotContain("Teléfono:", "\nComentarios\n", "null", "sin comentarios");
        assertThat(email.html()).doesNotContain(">Teléfono<", ">Comentarios<", ">null<", "sin comentarios");
    }

    private static ContactRequest request(String phone, String message) {
        ContactRequest request = new ContactRequest();
        request.setId(REQUEST_ID);
        request.setStatus(ContactRequestStatus.RECEIVED);
        request.setFullName("Ana <script>alert(1)</script> Pérez");
        request.setBusinessName("Café & Compañía <b>Central</b>");
        request.setEmail("ana@example.test");
        request.setPhone(phone);
        request.setIndustry("retail");
        request.setModules(String.join(",", ContactCatalog.MODULES));
        request.setMessage(message);
        request.setConsentVersion("2026-10");
        request.setConsentAt(Instant.parse("2026-10-18T16:29:00Z"));
        request.setCreatedAt(Instant.parse("2026-10-18T16:30:00Z"));
        return request;
    }
}

package com.omniretail.backend.shared.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class EmailLayoutTest {

    private static final String SCRIPT = "<script>alert(1)</script>";
    private static final String ESCAPED_SCRIPT = "&lt;script&gt;alert(1)&lt;/script&gt;";

    @Test
    void escapesEyebrowTitleAndEveryBlock() {
        String html = EmailLayout.render("Tienda " + SCRIPT, "Titulo <b>x</b>", List.of(
                EmailLayout.paragraph("Hola ", "Ana " + SCRIPT, ":"),
                EmailLayout.paragraph("<img src=x onerror=alert(1)>"),
                EmailLayout.notice("<iframe src=\"https://malo.example\"></iframe>"),
                EmailLayout.code("<b>123456</b>"),
                EmailLayout.button("Entrar " + SCRIPT, "https://tienda.example/x")));

        assertThat(html).doesNotContain(SCRIPT, "<b>", "<img", "<iframe")
                .contains("Tienda " + ESCAPED_SCRIPT, "Titulo &lt;b&gt;x&lt;/b&gt;", "Ana " + ESCAPED_SCRIPT,
                        "&lt;img src=x onerror=alert(1)&gt;", "&lt;iframe", "&lt;b&gt;123456&lt;/b&gt;",
                        "Entrar " + ESCAPED_SCRIPT);
    }

    @Test
    void buttonShowsTheLinkAsButtonAndAsTextBelow() {
        String html = EmailLayout.render(null, "Verifica", List.of(
                EmailLayout.button("Verificar", "https://app.example/verificar-correo/abc_123-XYZ")));

        assertThat(html).contains(
                "href=\"https://app.example/verificar-correo/abc_123-XYZ\"",
                ">Verificar</a>",
                ">https://app.example/verificar-correo/abc_123-XYZ</a>",
                "Si el botón no abre");
    }

    @Test
    void buttonUrlCannotBreakOutOfTheHrefAttribute() {
        String html = EmailLayout.render(null, "x", List.of(
                EmailLayout.button("Abrir", "https://app.example/\"onmouseover=\"alert(1)")));

        assertThat(html).doesNotContain("\"onmouseover=\"")
                .contains("href=\"https://app.example/&quot;onmouseover=&quot;alert(1)\"");
    }

    @Test
    void buttonRejectsNonHttpLinks() {
        assertThatThrownBy(() -> EmailLayout.button("Abrir", "javascript:alert(1)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmailLayout.button("Abrir", " JavaScript:alert(1)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmailLayout.button("Abrir", "data:text/html,hola"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmailLayout.button("Abrir", "//malo.example"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmailLayout.button("Abrir", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void layoutUsesInlineStylesMobileWidthAndDefaultEyebrow() {
        String html = EmailLayout.render("  ", "Hola", List.of(EmailLayout.code("123456")));

        assertThat(html).contains("<meta name=\"viewport\"", "max-width:600px", ">OmniRetail</div>",
                "font-size:32px", ">123456</div>", "Mensaje generado automaticamente por OmniRetail.")
                .doesNotContain("<link", "<style", "<script");
    }

    @Test
    void brandedFooterDetailsAndFreeTextAreEscaped() {
        String html = EmailLayout.render("MARJYM", "Solicitud", List.of(
                EmailLayout.sectionTitle("Datos <b>principales</b>"),
                EmailLayout.details(List.of(
                        new String[] {"Empresa", "Comercio <script>alert(1)</script>"},
                        new String[] {"Ausente", " "})),
                EmailLayout.textPanel("Línea <img src=x>\nDos")), "MARJYM <b>pie</b>");

        assertThat(html).contains(
                        "Datos &lt;b&gt;principales&lt;/b&gt;",
                        "Comercio &lt;script&gt;alert(1)&lt;/script&gt;",
                        "Línea &lt;img src=x&gt;\nDos",
                        "MARJYM &lt;b&gt;pie&lt;/b&gt;")
                .doesNotContain("<b>principales</b>", "<script>alert(1)</script>", "<img src=x>", ">Ausente<");
    }
}

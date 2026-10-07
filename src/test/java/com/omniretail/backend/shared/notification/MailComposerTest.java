package com.omniretail.backend.shared.notification;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class MailComposerTest {

    private final JavaMailSenderImpl sender = new JavaMailSenderImpl();
    private final UUID tenant = UUID.randomUUID();

    @Test
    void plainOnlyMessagesKeepTheirOriginalShape() throws Exception {
        MimeMessage mime = MailComposer.compose(sender, "noreply@omni.test", null,
                EmailMessage.text(tenant, EmailPurpose.PASSWORD_RESET, "a@b.com", "Asunto", "Hola"));

        mime.saveChanges();
        assertThat(mime.isMimeType("text/plain")).isTrue();
        assertThat(mime.getContent()).isEqualTo("Hola");
    }

    @Test
    void plainPlusHtmlIsMultipartAlternativeWithoutAttachments() throws Exception {
        MimeMessage mime = MailComposer.compose(sender, "noreply@omni.test", "Omni",
                new EmailMessage(tenant, EmailPurpose.PURCHASE_ORDER, "a@b.com", "Asunto", "Hola", "<p>Hola</p>"));

        mime.saveChanges();
        assertThat(mime.getContent()).isInstanceOf(Multipart.class);
        assertThat(attachmentsOf(mime)).isEmpty();
        assertThat(allText(mime)).contains("Hola").contains("<p>Hola</p>");
    }

    @Test
    void attachmentsAreAddedWithSafeFilenameAndMediaType() throws Exception {
        byte[] pdf = "%PDF-1.4 contenido".getBytes();
        EmailMessage message = new EmailMessage(
                tenant, EmailPurpose.PURCHASE_ORDER, "a@b.com", "Asunto", "Hola", "<p>Hola</p>",
                List.of(), List.of(new EmailAttachmentContent("../OC 001\r\n.pdf", "application/pdf", pdf)));

        MimeMessage mime = MailComposer.compose(sender, "noreply@omni.test", null, message);

        mime.saveChanges();
        List<BodyPart> attachments = attachmentsOf(mime);
        assertThat(attachments).singleElement().satisfies(part -> {
            // "../OC 001\r\n.pdf": separadores, espacios y saltos de línea se reemplazan y los puntos iniciales se quitan.
            assertThat(part.getFileName()).isEqualTo("_OC_001__.pdf")
                    .doesNotContain("/", "\r", "\n", " ");
            assertThat(part.getContentType()).startsWith("application/pdf");
            assertThat(part.getInputStream().readAllBytes()).isEqualTo(pdf);
        });
        assertThat(allText(mime)).contains("<p>Hola</p>");
    }

    @Test
    void plainTextWithAttachmentStillSendsTheBodyAndSanitizesEmptyNames() throws Exception {
        EmailMessage message = new EmailMessage(
                tenant, EmailPurpose.PURCHASE_ORDER, "a@b.com", "Asunto", "Solo texto", null,
                List.of(), List.of(new EmailAttachmentContent("***", "application/pdf", new byte[] {1, 2, 3})));

        MimeMessage mime = MailComposer.compose(sender, "noreply@omni.test", null, message);

        mime.saveChanges();
        assertThat(allText(mime)).contains("Solo texto");
        assertThat(attachmentsOf(mime)).singleElement().satisfies(part ->
                assertThat(part.getFileName()).isNotBlank().doesNotContain("*"));
        assertThat(MailComposer.safeFilename("")).isEqualTo("adjunto");
        assertThat(MailComposer.safeFilename(".hidden.pdf")).isEqualTo("hidden.pdf");
    }

    private static List<BodyPart> attachmentsOf(Part part) throws Exception {
        List<BodyPart> found = new ArrayList<>();
        collect(part, found, false);
        return found;
    }

    private static String allText(Part part) throws Exception {
        List<BodyPart> found = new ArrayList<>();
        collect(part, found, true);
        StringBuilder text = new StringBuilder();
        for (BodyPart bodyPart : found) {
            text.append(bodyPart.getContent());
        }
        return text.toString();
    }

    private static void collect(Part part, List<BodyPart> found, boolean text) throws Exception {
        Object content = part.getContent();
        if (content instanceof Multipart multipart) {
            for (int index = 0; index < multipart.getCount(); index++) {
                BodyPart child = multipart.getBodyPart(index);
                collect(child, found, text);
            }
            return;
        }
        if (part instanceof BodyPart bodyPart) {
            boolean attachment = Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || part.getFileName() != null;
            if (text ? !attachment && content instanceof String : attachment) {
                found.add(bodyPart);
            }
        }
    }
}

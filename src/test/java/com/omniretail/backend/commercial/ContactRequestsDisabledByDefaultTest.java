package com.omniretail.backend.commercial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.shared.notification.CapturingEmailSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Configuracion por defecto (la de cualquier instalacion de cliente): el servicio comercial no existe. Sin ruta,
 * sin jobs, sin listeners y sin correos comerciales.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CapturingEmailSender.Config.class})
class ContactRequestsDisabledByDefaultTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationContext context;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CapturingEmailSender emails;

    @Test
    void theEndpointIsNotAvailableAndStoresNothing() throws Exception {
        Long before = jdbc.queryForObject("SELECT COUNT(*) FROM contact_requests", Long.class);
        String email = ContactRequestPayloads.uniqueEmail();

        mockMvc.perform(post(ContactRequestPayloads.PATH)
                        .contentType(APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(ContactRequestPayloads.valid(email, "Negocio X"))))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contact_requests", Long.class)).isEqualTo(before);
        assertThat(emails.settledMessagesTo(email)).isEmpty();
    }

    @Test
    void noCommercialComponentIsRegistered() {
        assertThat(context.getBeanNamesForType(ContactRequestController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ContactRequestService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ContactRequestMailService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ContactRequestMailListener.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ContactRequestMailRetryJob.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ContactRequestPurgeJob.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ContactRequestBodyLimitFilter.class)).isEmpty();
        assertThat(context.getBeanNamesForType(CaptchaVerifier.class)).isEmpty();
    }

    @Test
    void thePropertiesRecordExistsButIsDisabled() {
        assertThat(context.getBean(ContactRequestProperties.class).enabled()).isFalse();
    }

    @Test
    void theMigrationLeavesAnEmptyTableForExistingDatabasesAndRecordsItsChangeset() {
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM databasechangelog WHERE id = '063-commercial-contact-requests'",
                        Long.class))
                .isOne();
        // El archivo fisico es 064-...; la identidad registrada (id, author, filename) sigue siendo la original.
        assertThat(jdbc.queryForList(
                        "SELECT id || '|' || author || '|' || filename FROM databasechangelog "
                                + "WHERE id IN ('063-commercial-contact-requests', "
                                + "'063-subscription-provisioning-operations') ORDER BY id",
                        String.class))
                .containsExactly(
                        "063-commercial-contact-requests|omniretail|"
                                + "db/changelog/changes/063-commercial-contact-requests.yaml",
                        "063-subscription-provisioning-operations|omniretail|"
                                + "db/changelog/changes/063-commercial-contact-requests.yaml");
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM pg_constraint WHERE conname IN "
                                + "('uk_contact_requests_idempotency_key', 'uk_contact_requests_dedupe')",
                        Long.class))
                .isEqualTo(2);
        // El historico de inventario y sus identidades siguen intactos.
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM databasechangelog WHERE id = '058-inventory-balance-regularizations'",
                        Long.class))
                .isOne();
    }
}

package com.omniretail.backend.commercial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.exception.FieldValidationException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Orden de las comprobaciones y reglas del registro, sin base de datos. */
class ContactRequestServiceTest {

    private static final String CLIENT_IP = "203.0.113.77";

    private final ContactRequestRepository repository = mock(ContactRequestRepository.class);
    private final CaptchaVerifier captcha = mock(CaptchaVerifier.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private ContactRequestService service;

    @BeforeEach
    void setUp() {
        ContactRequestProperties properties = new ContactRequestProperties(
                true, "equipo@marjym.test", "secreto-de-prueba-con-mas-de-treinta-y-dos-caracteres", null, 10, 3, 200,
                null, null, null, null, null, null, null, null);
        service = new ContactRequestService(repository, properties, captcha, jdbc, publisher, transactionManager);
        when(captcha.verify(any(), any())).thenReturn(true);
    }

    private static CreateContactRequest request(String email, String website) {
        return new CreateContactRequest(
                "  Ana Pérez ", " Mi Negocio ", email, " 5555-1234 ", " Retail ", List.of(" inventory ", "pos"),
                "  Hola  ", true, "2026-10", website, "token");
    }

    @Test
    void aRepeatedIdempotencyKeyReturnsTheSameRequestWithoutQuotasCaptchaOrEmails() {
        UUID key = UUID.randomUUID();
        ContactRequest existing = new ContactRequest();
        existing.setId(UUID.randomUUID());
        when(repository.findByIdempotencyKey(key)).thenReturn(Optional.of(existing));

        ContactRequestAccepted accepted = service.submit(request("a@b.co", null), key, CLIENT_IP);

        assertThat(accepted.requestId()).isEqualTo(existing.getId());
        assertThat(accepted.status()).isEqualTo("RECEIVED");
        verify(repository, never()).countByIpHashAndCreatedAtAfter(anyString(), any());
        verifyNoInteractions(captcha, jdbc, publisher);
    }

    @Test
    void theSameEmailAndBusinessTheSameDayWithAnotherKeyReturnsTheEarlierRequestWithoutNewWork() {
        ContactRequest earlier = new ContactRequest();
        earlier.setId(UUID.randomUUID());
        when(repository.findByDedupeKeyAndDedupeDay(anyString(), any())).thenReturn(Optional.of(earlier));

        ContactRequestAccepted accepted = service.submit(request("a@b.co", null), UUID.randomUUID(), CLIENT_IP);

        assertThat(accepted.requestId()).isEqualTo(earlier.getId());
        verifyNoInteractions(captcha, jdbc, publisher);
    }

    @Test
    void aDifferentKeyAndBusinessIsANewRegistrationThatSchedulesItsOwnEmails() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        CreateContactRequest other = new CreateContactRequest(
                "Ana Pérez", "Otro Negocio", "a@b.co", "55551234", "retail", List.of("pos"), null, true, "1", null,
                "token");

        ContactRequestAccepted first = service.submit(request("a@b.co", null), UUID.randomUUID(), CLIENT_IP);
        ContactRequestAccepted second = service.submit(other, UUID.randomUUID(), CLIENT_IP);

        assertThat(second.requestId()).isNotEqualTo(first.requestId());
        verify(publisher).publishEvent(new ContactRequestRegisteredEvent(first.requestId()));
        verify(publisher).publishEvent(new ContactRequestRegisteredEvent(second.requestId()));
    }

    @Test
    void theIpQuotaIsPersistentAndStopsBeforeCaptchaAndStorage() {
        when(repository.countByIpHashAndCreatedAtAfter(anyString(), any(Instant.class))).thenReturn(10L);

        assertThatThrownBy(() -> service.submit(request("a@b.co", null), null, CLIENT_IP))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(error.getCode()).isEqualTo("CONTACT_QUOTA_EXCEEDED");
                });
        verifyNoInteractions(captcha, jdbc, publisher);
    }

    @Test
    void theEmailQuotaStopsBeforeCaptchaAndStorage() {
        when(repository.countByEmailAndCreatedAtAfter(anyString(), any(Instant.class))).thenReturn(3L);

        assertThatThrownBy(() -> service.submit(request("a@b.co", null), null, CLIENT_IP))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("CONTACT_QUOTA_EXCEEDED"));
        verifyNoInteractions(captcha, jdbc, publisher);
    }

    @Test
    void theGlobalDailyCapCutsOffNewRequestsWithoutStoringAnything() {
        when(repository.countByStatusAndCreatedAtAfter(any(), any(Instant.class))).thenReturn(200L);

        assertThatThrownBy(() -> service.submit(request("a@b.co", null), null, CLIENT_IP))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getCode()).isEqualTo("CONTACT_REQUESTS_UNAVAILABLE");
                });
        verifyNoInteractions(captcha, jdbc, publisher);
    }

    @Test
    void aFailedCaptchaIsRejectedBeforeStoring() {
        when(captcha.verify(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.submit(request("a@b.co", null), null, CLIENT_IP))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        error -> assertThat(error.getFields()).containsKey("captchaToken"));
        verifyNoInteractions(jdbc, publisher);
    }

    @Test
    void normalizesTheDataStoresOnlyAnIpHashAndSchedulesTheEmailsOnce() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        ContactRequestAccepted accepted = service.submit(request("ANA@Example.TEST", null), null, CLIENT_IP);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        Object[] row = args.getValue();
        assertThat(row[0]).isEqualTo(accepted.requestId());
        assertThat(row[1]).isEqualTo("RECEIVED");
        assertThat(row[2]).isEqualTo("Ana Pérez");
        assertThat(row[3]).isEqualTo("Mi Negocio");
        assertThat(row[4]).isEqualTo("ana@example.test");
        assertThat(row[6]).isEqualTo("retail");
        assertThat(row[7]).isEqualTo("inventory,pos");
        assertThat(row[8]).isEqualTo("Hola");
        assertThat((String) row[14]).hasSize(64).isNotEqualTo(CLIENT_IP).doesNotContain(CLIENT_IP);
        assertThat(row[15]).isEqualTo("PENDING");
        assertThat(row[17]).isEqualTo("PENDING");
        verify(jdbc).execute(contains("pg_advisory_xact_lock"));
        // Una lectura rapida antes del captcha y otra autoritativa dentro de la seccion critica.
        verify(repository, times(2)).countByIpHashAndCreatedAtAfter(anyString(), any(Instant.class));
        verify(repository, times(2)).countByEmailAndCreatedAtAfter(anyString(), any(Instant.class));
        verify(repository, times(2)).countByStatusAndCreatedAtAfter(any(), any(Instant.class));
        verify(publisher).publishEvent(new ContactRequestRegisteredEvent(accepted.requestId()));
    }

    @Test
    void aHoneypotIsStoredAsSpamWithoutCaptchaEmailQuotasOrEvents() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        service.submit(request("bot@example.test", "http://spam.example"), null, CLIENT_IP);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        assertThat(args.getValue()[1]).isEqualTo("SPAM");
        assertThat(args.getValue()[12]).isNull(); // sin deduplicacion
        assertThat(args.getValue()[15]).isEqualTo("NOT_REQUIRED");
        assertThat(args.getValue()[17]).isEqualTo("NOT_REQUIRED");
        verifyNoInteractions(captcha, publisher);
        verify(repository, never()).countByEmailAndCreatedAtAfter(anyString(), any());
        verify(repository, never()).countByStatusAndCreatedAtAfter(any(), any());
    }

    @Test
    void aConflictingInsertReturnsTheWinnerAndSchedulesNothing() {
        UUID key = UUID.randomUUID();
        ContactRequest winner = new ContactRequest();
        winner.setId(UUID.randomUUID());
        when(repository.findByIdempotencyKey(key))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(winner));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        ContactRequestAccepted accepted = service.submit(request("a@b.co", null), key, CLIENT_IP);

        assertThat(accepted.requestId()).isEqualTo(winner.getId());
        verify(jdbc).update(anyString(), any(Object[].class));
        verifyNoInteractions(publisher);
    }

    @Test
    void ifTheConflictCannotBeResolvedNothingIsAcknowledged() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        assertThatThrownBy(() -> service.submit(request("a@b.co", null), null, CLIENT_IP))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(publisher);
    }

    @Test
    void closedListsRejectUnknownIndustriesAndModules() {
        CreateContactRequest industry = new CreateContactRequest(
                "Ana Pérez", "Negocio", "a@b.co", "55551234", "otra-cosa", List.of("pos"), null, true, "1", null, null);
        CreateContactRequest module = new CreateContactRequest(
                "Ana Pérez", "Negocio", "a@b.co", "55551234", "retail", List.of("crear-tenant"), null, true, "1", null, null);
        CreateContactRequest repeated = new CreateContactRequest(
                "Ana Pérez", "Negocio", "a@b.co", "55551234", "retail", List.of("pos", "pos"), null, true, "1", null, null);
        CreateContactRequest control = new CreateContactRequest(
                "Ana Pérez", "Negocio", "a@b.co", "55551234", "retail", List.of("pos"), "hola\u0007", true, "1", null, null);

        for (CreateContactRequest invalid : List.of(industry, module, repeated, control)) {
            assertThatThrownBy(() -> service.submit(invalid, null, CLIENT_IP))
                    .isInstanceOf(FieldValidationException.class);
        }
        verifyNoInteractions(jdbc, publisher);
    }

    @Test
    void theModuleListIsTheRealCapabilityCatalogue() {
        assertThat(ContactCatalog.MODULES).contains("inventory", "pos", "ecommerce", "reports.advanced");
        assertThat(ContactCatalog.MODULES).doesNotHaveDuplicates();
    }
}

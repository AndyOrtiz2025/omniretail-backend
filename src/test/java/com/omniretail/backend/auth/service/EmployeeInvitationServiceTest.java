package com.omniretail.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.entity.AccountStatus;
import com.omniretail.backend.auth.entity.AuthAccount;
import com.omniretail.backend.auth.entity.EmployeeInvitation;
import com.omniretail.backend.auth.repository.AuthAccountRepository;
import com.omniretail.backend.auth.repository.EmployeeInvitationRepository;
import com.omniretail.backend.shared.config.FrontendProperties;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.notification.EmailRequestedEvent;
import com.omniretail.backend.shared.security.EmployeeInviteResult;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/** Escritas antes del servicio; no ejecutadas por restriccion explicita de esta sesion. */
@ExtendWith(MockitoExtension.class)
class EmployeeInvitationServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String EMAIL = "empleado@example.com";

    @Mock
    private UserRepository userRepository;
    @Mock
    private EmployeeInvitationRepository invitationRepository;
    @Mock
    private AuthAccountRepository accountRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private BCryptPasswordEncoder passwordEncoder;
    private FrontendProperties frontendProperties;
    private EmployeeInvitationService service;

    @BeforeEach
    void setUp() {
        passwordEncoder = spy(new BCryptPasswordEncoder());
        frontendProperties = new FrontendProperties("https://app.example.com/");
        service = new EmployeeInvitationService(userRepository, invitationRepository, accountRepository,
                passwordEncoder, eventPublisher, frontendProperties);
    }

    @Test
    void inviteCreatesPendingAccountAndHashOnlyInvitationWithExact48HourLifetime() throws Exception {
        stubEmployee();
        when(invitationRepository.findByUserIdAndAcceptedAtIsNullAndSupersededAtIsNull(USER_ID))
                .thenReturn(List.of());
        when(accountRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.empty());

        Instant before = Instant.now();
        EmployeeInviteResult result = service.inviteEmployee(TENANT_ID, USER_ID);
        Instant after = Instant.now();

        ArgumentCaptor<AuthAccount> accountCaptor = ArgumentCaptor.forClass(AuthAccount.class);
        verify(accountRepository).save(accountCaptor.capture());
        AuthAccount account = accountCaptor.getValue();
        assertThat(account.getUserId()).isEqualTo(USER_ID);
        assertThat(account.getEmail()).isEqualTo(EMAIL);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.password_reset_required);
        assertThat(account.getFailedLoginAttempts()).isZero();
        assertThat(account.getLockedUntil()).isNull();
        assertThat(account.getPasswordChangedAt()).isNull();
        assertThat(account.getLastLoginAt()).isNull();

        ArgumentCaptor<CharSequence> passwordCaptor = ArgumentCaptor.forClass(CharSequence.class);
        verify(passwordEncoder).encode(passwordCaptor.capture());
        String randomPassword = passwordCaptor.getValue().toString();
        assertThat(randomPassword).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(result.invitationToken());
        assertThat(account.getPasswordHash()).startsWith("$2");
        assertThat(passwordEncoder.matches(randomPassword, account.getPasswordHash())).isTrue();

        EmployeeInvitation invitation = savedInvitation();
        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.invitationToken()).matches("[A-Za-z0-9_-]{43}");
        assertThat(invitation.getUserId()).isEqualTo(USER_ID);
        assertThat(invitation.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(invitation.getCreatedAt()).isBetween(before, after);
        assertThat(invitation.getExpiresAt()).isEqualTo(invitation.getCreatedAt().plus(Duration.ofHours(48)));
        assertThat(result.expiresAt()).isEqualTo(invitation.getExpiresAt());
        assertThat(invitation.getAcceptedAt()).isNull();
        assertThat(invitation.getSupersededAt()).isNull();
        assertThat(invitation.getTokenHash()).matches("[0-9a-f]{64}")
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(result.invitationToken().getBytes(StandardCharsets.UTF_8))));
        assertNoClearToken(account, result.invitationToken());
        assertNoClearToken(invitation, result.invitationToken());

        EmailRequestedEvent event = requestedEmail();
        assertThat(event.message().to()).isEqualTo(EMAIL);
        assertThat(event.message().subject()).isEqualTo("Activa tu cuenta de empleado");
        assertThat(event.message().body()).contains("Ana Empleada",
                frontendProperties.link("/activar-cuenta/" + result.invitationToken()), "48 horas")
                .doesNotContain(randomPassword, account.getPasswordHash(), invitation.getTokenHash());
    }

    @Test
    void reinviteSupersedesPreviousInvitationWithSameNowAndGeneratesDifferentToken() {
        stubEmployee();
        EmployeeInvitation previous = invitation("previous-token");
        when(invitationRepository.findByUserIdAndAcceptedAtIsNullAndSupersededAtIsNull(USER_ID))
                .thenReturn(List.of(previous));
        when(accountRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(pendingAccount()));

        EmployeeInviteResult result = service.inviteEmployee(TENANT_ID, USER_ID);

        EmployeeInvitation next = savedInvitation();
        assertThat(previous.getSupersededAt()).isEqualTo(next.getCreatedAt());
        assertThat(previous.getAcceptedAt()).isNull();
        assertThat(next.getSupersededAt()).isNull();
        assertThat(next.getTokenHash()).isNotEqualTo(previous.getTokenHash())
                .isEqualTo(AuthTokens.hash(result.invitationToken()));
        verify(accountRepository, never()).save(any(AuthAccount.class));
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void reinviteSynchronizesPendingAccountEmailFromAdministrativeUserWithoutChangingPassword() {
        stubEmployee();
        AuthAccount account = pendingAccount();
        account.setEmail("previous@example.com");
        String previousHash = account.getPasswordHash();
        when(invitationRepository.findByUserIdAndAcceptedAtIsNullAndSupersededAtIsNull(USER_ID))
                .thenReturn(List.of());
        when(accountRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(account));

        service.inviteEmployee(TENANT_ID, USER_ID);

        assertThat(account.getEmail()).isEqualTo(EMAIL);
        assertThat(account.getPasswordHash()).isEqualTo(previousHash);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.password_reset_required);
        assertThat(requestedEmail().message().to()).isEqualTo(EMAIL);
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void locksUserThenInvitationsThenAccount() {
        stubEmployee();
        when(invitationRepository.findByUserIdAndAcceptedAtIsNullAndSupersededAtIsNull(USER_ID))
                .thenReturn(List.of());
        when(accountRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(pendingAccount()));

        service.inviteEmployee(TENANT_ID, USER_ID);

        InOrder locks = inOrder(userRepository, invitationRepository, accountRepository);
        locks.verify(userRepository).findByTenantIdAndIdForUpdate(TENANT_ID, USER_ID);
        locks.verify(invitationRepository).findByUserIdAndAcceptedAtIsNullAndSupersededAtIsNull(USER_ID);
        locks.verify(accountRepository).findByUserIdForUpdate(USER_ID);
        locks.verify(invitationRepository).save(any(EmployeeInvitation.class));
        verify(userRepository, never()).findById(any(UUID.class));
    }

    @Test
    void twoSequentialReinvitationsLeaveOnlyLatestInvitationPending() {
        stubEmployee();
        EmployeeInvitation original = invitation("original-token");
        List<EmployeeInvitation> persisted = new ArrayList<>(List.of(original));
        when(invitationRepository.findByUserIdAndAcceptedAtIsNullAndSupersededAtIsNull(USER_ID))
                .thenAnswer(ignored -> persisted.stream()
                        .filter(invitation -> invitation.getAcceptedAt() == null && invitation.getSupersededAt() == null)
                        .toList());
        when(invitationRepository.save(any(EmployeeInvitation.class))).thenAnswer(invocation -> {
            EmployeeInvitation added = invocation.getArgument(0);
            persisted.add(added);
            return added;
        });
        when(accountRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(pendingAccount()));

        EmployeeInviteResult first = service.inviteEmployee(TENANT_ID, USER_ID);
        EmployeeInviteResult second = service.inviteEmployee(TENANT_ID, USER_ID);

        assertThat(persisted).hasSize(3);
        assertThat(original.getSupersededAt()).isEqualTo(persisted.get(1).getCreatedAt());
        assertThat(persisted.get(1).getSupersededAt()).isEqualTo(persisted.get(2).getCreatedAt());
        assertThat(persisted.stream().filter(invitation -> invitation.getSupersededAt() == null).toList())
                .containsExactly(persisted.get(2));
        assertThat(first.invitationToken()).isNotEqualTo(second.invitationToken());
        assertThat(persisted.get(2).getTokenHash()).isEqualTo(AuthTokens.hash(second.invitationToken()));
        verify(eventPublisher, times(2)).publishEvent(any(EmailRequestedEvent.class));
    }

    @Test
    void missingUserReturnsGenericUserNotFoundBeforeTouchingAuth() {
        when(userRepository.findByTenantIdAndIdForUpdate(TENANT_ID, USER_ID)).thenReturn(Optional.empty());

        assertError(TENANT_ID, HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Usuario no encontrado.");

        verifyNoInteractions(invitationRepository, accountRepository, passwordEncoder, eventPublisher);
    }

    @Test
    void crossTenantReturnsSameUserNotFoundUsingOnlyTenantScopedLookup() {
        UUID otherTenant = UUID.randomUUID();
        when(userRepository.findByTenantIdAndIdForUpdate(otherTenant, USER_ID)).thenReturn(Optional.empty());

        assertError(otherTenant, HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Usuario no encontrado.");

        verify(userRepository).findByTenantIdAndIdForUpdate(otherTenant, USER_ID);
        verify(userRepository, never()).findById(any(UUID.class));
        verifyNoInteractions(invitationRepository, accountRepository, passwordEncoder, eventPublisher);
    }

    @Test
    void customerReturnsSameUserNotFoundBeforeTouchingAuth() {
        User customer = employee();
        customer.setType(UserType.customer);
        when(userRepository.findByTenantIdAndIdForUpdate(TENANT_ID, USER_ID)).thenReturn(Optional.of(customer));

        assertError(TENANT_ID, HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Usuario no encontrado.");

        verifyNoInteractions(invitationRepository, accountRepository, passwordEncoder, eventPublisher);
    }

    @Test
    void activeAccountReturnsExactConflictWithoutSavingOrPublishing() {
        stubExistingAccount(AccountStatus.active);

        assertError(TENANT_ID, HttpStatus.CONFLICT, "ACCOUNT_ALREADY_ACTIVE",
                "Este empleado ya tiene una cuenta activa.");

        verify(accountRepository, never()).save(any(AuthAccount.class));
        verify(invitationRepository, never()).save(any(EmployeeInvitation.class));
        verifyNoInteractions(passwordEncoder, eventPublisher);
    }

    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"active", "password_reset_required"},
            mode = EnumSource.Mode.EXCLUDE)
    void nonPendingAccountReturnsExactBadRequestWithoutSavingOrPublishing(AccountStatus status) {
        stubExistingAccount(status);

        assertError(TENANT_ID, HttpStatus.BAD_REQUEST, "INVITATION_NOT_ALLOWED",
                "No se puede invitar a este empleado en su estado actual.");

        verify(accountRepository, never()).save(any(AuthAccount.class));
        verify(invitationRepository, never()).save(any(EmployeeInvitation.class));
        verifyNoInteractions(passwordEncoder, eventPublisher);
    }

    private void stubEmployee() {
        when(userRepository.findByTenantIdAndIdForUpdate(TENANT_ID, USER_ID)).thenReturn(Optional.of(employee()));
    }

    private static User employee() {
        User user = User.builder().name("Ana Empleada").email(EMAIL).type(UserType.employee).build();
        user.setTenantId(TENANT_ID);
        ReflectionTestUtils.setField(user, "id", USER_ID);
        return user;
    }

    private static AuthAccount pendingAccount() {
        return AuthAccount.builder().userId(USER_ID).email(EMAIL).passwordHash("existing-bcrypt-hash")
                .status(AccountStatus.password_reset_required).build();
    }

    private static EmployeeInvitation invitation(String token) {
        return EmployeeInvitation.builder().userId(USER_ID).tenantId(TENANT_ID).tokenHash(AuthTokens.hash(token))
                .createdAt(Instant.parse("2026-09-29T10:00:00Z"))
                .expiresAt(Instant.parse("2026-10-01T10:00:00Z")).build();
    }

    private void stubExistingAccount(AccountStatus status) {
        stubEmployee();
        when(invitationRepository.findByUserIdAndAcceptedAtIsNullAndSupersededAtIsNull(USER_ID))
                .thenReturn(List.of());
        AuthAccount account = pendingAccount();
        account.setStatus(status);
        when(accountRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(account));
    }

    private void assertError(UUID tenantId, HttpStatus status, String code, String message) {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.inviteEmployee(tenantId, USER_ID));
        assertThat(exception.getStatus()).isEqualTo(status);
        assertThat(exception.getCode()).isEqualTo(code);
        assertThat(exception.getMessage()).isEqualTo(message);
    }

    private EmployeeInvitation savedInvitation() {
        ArgumentCaptor<EmployeeInvitation> captor = ArgumentCaptor.forClass(EmployeeInvitation.class);
        verify(invitationRepository).save(captor.capture());
        return captor.getValue();
    }

    private EmailRequestedEvent requestedEmail() {
        ArgumentCaptor<EmailRequestedEvent> captor = ArgumentCaptor.forClass(EmailRequestedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue();
    }

    private static void assertNoClearToken(Object entity, String token) throws IllegalAccessException {
        for (Class<?> type = entity.getClass(); type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() == String.class && !Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    String value = (String) field.get(entity);
                    if (value != null) {
                        assertThat(value).as("campo persistido %s", field.getName()).doesNotContain(token);
                    }
                }
            }
        }
    }
}

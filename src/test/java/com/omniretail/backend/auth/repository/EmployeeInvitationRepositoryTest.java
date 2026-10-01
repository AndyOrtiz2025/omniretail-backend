package com.omniretail.backend.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.entity.AuthAccount;
import com.omniretail.backend.auth.entity.EmployeeInvitation;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class EmployeeInvitationRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

    @Autowired private EmployeeInvitationRepository invitations;
    @Autowired private UserRepository users;
    @Autowired private TenantRepository tenants;
    @Autowired private AuthAccountRepository accounts;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void schemaHasExactlyTheInvitationColumnsAndNoUpdatedAt() {
        List<String> columns = jdbc.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'employee_invitations'",
                String.class);

        assertThat(columns).containsExactlyInAnyOrder(
                "id", "user_id", "tenant_id", "token_hash", "created_at", "expires_at",
                "accepted_at", "superseded_at").doesNotContain("updated_at");
        assertThat(jdbc.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'employee_invitations' "
                        + "and data_type = 'timestamp with time zone'", String.class))
                .containsExactlyInAnyOrder("created_at", "expires_at", "accepted_at", "superseded_at");
        assertThat(jdbc.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'employee_invitations' "
                        + "and is_nullable = 'NO'", String.class))
                .containsExactlyInAnyOrder("id", "user_id", "tenant_id", "token_hash", "created_at", "expires_at");
        assertThat(jdbc.queryForObject(
                "select character_maximum_length from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'employee_invitations' "
                        + "and column_name = 'token_hash'", Integer.class)).isEqualTo(64);
    }

    @Test
    void persistsEveryFieldAndAllowsNullableLifecycleTimestamps() {
        User employee = persistUser(persistTenant().getId(), UserType.employee);
        EmployeeInvitation invitation = persistInvitation(employee, NOW);
        UUID id = invitation.getId();
        entityManager.clear();

        EmployeeInvitation stored = invitations.findById(id).orElseThrow();
        assertThat(stored.getId()).isNotNull();
        assertThat(stored.getUserId()).isEqualTo(employee.getId());
        assertThat(stored.getTenantId()).isEqualTo(employee.getTenantId());
        assertThat(stored.getTokenHash()).isEqualTo(invitation.getTokenHash());
        assertThat(stored.getCreatedAt()).isEqualTo(NOW);
        assertThat(stored.getExpiresAt()).isEqualTo(NOW.plusSeconds(48 * 60 * 60));
        assertThat(stored.getAcceptedAt()).isNull();
        assertThat(stored.getSupersededAt()).isNull();

        stored.setAcceptedAt(NOW.plusSeconds(60));
        stored.setSupersededAt(NOW.plusSeconds(120));
        invitations.saveAndFlush(stored);
        entityManager.clear();

        EmployeeInvitation updated = invitations.findById(id).orElseThrow();
        assertThat(updated.getAcceptedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(updated.getSupersededAt()).isEqualTo(NOW.plusSeconds(120));
        assertThat(updated.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void databaseGeneratesIdAndCreatedAtWhenOmitted() {
        User employee = persistUser(persistTenant().getId(), UserType.employee);
        UUID id = jdbc.queryForObject(
                "insert into employee_invitations (user_id, tenant_id, token_hash, expires_at) "
                        + "values (?, ?, ?, now() + interval '48 hours') returning id",
                UUID.class, employee.getId(), employee.getTenantId(), tokenHash());

        assertThat(id).isNotNull();
        assertThat(invitations.findById(id).orElseThrow().getCreatedAt()).isNotNull();
    }

    @Test
    void tokenHashIsUniqueAcrossUsersAndTenants() {
        User first = persistUser(persistTenant().getId(), UserType.employee);
        User second = persistUser(persistTenant().getId(), UserType.employee);
        EmployeeInvitation original = persistInvitation(first, NOW);
        EmployeeInvitation duplicate = newInvitation(second, NOW);
        duplicate.setTokenHash(original.getTokenHash());

        assertThatThrownBy(() -> invitations.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_employee_invitations_token_hash");
    }

    @Test
    void deletingUserCascadesToInvitations() {
        User employee = persistUser(persistTenant().getId(), UserType.employee);
        UUID invitationId = persistInvitation(employee, NOW).getId();

        users.delete(employee);
        users.flush();
        entityManager.clear();

        assertThat(invitations.findById(invitationId)).isEmpty();
    }

    @Test
    void deletingUnrelatedInvitationTenantCascadesToInvitations() {
        User employee = persistUser(persistTenant().getId(), UserType.employee);
        Tenant invitationTenant = persistTenant();
        EmployeeInvitation invitation = newInvitation(employee, NOW);
        invitation.setTenantId(invitationTenant.getId());
        UUID invitationId = invitations.saveAndFlush(invitation).getId();

        // Las dos FKs son independientes; el servicio verificara la coincidencia de tenant.
        tenants.delete(invitationTenant);
        tenants.flush();
        entityManager.clear();

        assertThat(invitations.findById(invitationId)).isEmpty();
    }

    @Test
    void currentQueryFiltersAcceptedSupersededAndOtherUsersButNotExpiration() {
        UUID tenantId = persistTenant().getId();
        User employee = persistUser(tenantId, UserType.employee);
        User other = persistUser(tenantId, UserType.employee);
        EmployeeInvitation current = persistInvitation(employee, NOW);
        EmployeeInvitation expired = persistInvitation(employee, NOW.minusSeconds(72 * 60 * 60));
        EmployeeInvitation accepted = newInvitation(employee, NOW);
        accepted.setAcceptedAt(NOW);
        invitations.saveAndFlush(accepted);
        EmployeeInvitation superseded = newInvitation(employee, NOW);
        superseded.setSupersededAt(NOW);
        invitations.saveAndFlush(superseded);
        persistInvitation(other, NOW);

        assertThat(invitations.findByUserIdAndAcceptedAtIsNullAndSupersededAtIsNull(employee.getId()))
                .extracting(EmployeeInvitation::getId)
                .containsExactlyInAnyOrder(current.getId(), expired.getId());
        assertThat(invitations.findByUserIdAndAcceptedAtIsNullAndSupersededAtIsNull(UUID.randomUUID()))
                .isEmpty();
    }

    @Test
    void latestInvitationIsOrderedByCreatedAtIncludingHistory() {
        UUID tenantId = persistTenant().getId();
        User employee = persistUser(tenantId, UserType.employee);
        User other = persistUser(tenantId, UserType.employee);
        EmployeeInvitation latest = newInvitation(employee, NOW.plusSeconds(60));
        latest.setAcceptedAt(NOW.plusSeconds(120));
        invitations.saveAndFlush(latest);
        persistInvitation(employee, NOW.minusSeconds(60));
        persistInvitation(employee, NOW);
        persistInvitation(other, NOW.plusSeconds(180));

        assertThat(invitations.findTopByUserIdOrderByCreatedAtDesc(employee.getId()))
                .get().extracting(EmployeeInvitation::getId).isEqualTo(latest.getId());
        assertThat(invitations.findTopByUserIdOrderByCreatedAtDesc(UUID.randomUUID())).isEmpty();
    }

    @Test
    void batchQueriesFilterTenantTypeAndSelectedUserIds() {
        UUID tenantId = persistTenant().getId();
        User employee = persistUser(tenantId, UserType.employee);
        User unselected = persistUser(tenantId, UserType.employee);
        User customer = persistUser(tenantId, UserType.customer);
        User outsider = persistUser(persistTenant().getId(), UserType.employee);
        AuthAccount selected = persistAccount(employee);
        persistAccount(unselected);
        AuthAccount outsiderAccount = persistAccount(outsider);

        assertThat(users.findAllByTenantIdAndTypeAndIdIn(tenantId, UserType.employee,
                List.of(employee.getId(), customer.getId(), outsider.getId(), UUID.randomUUID())))
                .extracting(User::getId).containsExactly(employee.getId());
        assertThat(accounts.findAllByUserIdIn(List.of(employee.getId(), outsider.getId(), UUID.randomUUID())))
                .extracting(AuthAccount::getId).containsExactlyInAnyOrder(selected.getId(), outsiderAccount.getId());
        assertThat(users.findByTenantIdAndIdForUpdate(outsider.getTenantId(), employee.getId())).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void tokenLookupAcquiresPessimisticWriteUntilTransactionCompletes() throws SQLException {
        withCommittedFixture((employee, invitation) -> {
            newTransaction().executeWithoutResult(status -> {
                assertThat(invitations.findByTokenHashForUpdate(invitation.getTokenHash()))
                        .get().extracting(EmployeeInvitation::getId).isEqualTo(invitation.getId());
                assertRowLocked("employee_invitations", invitation.getId());
            });
            assertRowUnlocked("employee_invitations", invitation.getId());
        });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void tenantQualifiedUserLookupAcquiresPessimisticWriteUntilTransactionCompletes() throws SQLException {
        withCommittedFixture((employee, invitation) -> {
            newTransaction().executeWithoutResult(status -> {
                assertThat(users.findByTenantIdAndIdForUpdate(employee.getTenantId(), employee.getId()))
                        .get().extracting(User::getId).isEqualTo(employee.getId());
                assertRowLocked("users", employee.getId());
            });
            assertRowUnlocked("users", employee.getId());
        });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void currentInvitationLookupLocksEveryCurrentRowUntilTransactionCompletes() throws SQLException {
        withCommittedFixture((employee, invitation) -> {
            EmployeeInvitation second = newTransaction().execute(
                    status -> persistInvitation(employee, NOW.plusSeconds(1)));
            newTransaction().executeWithoutResult(status -> {
                assertThat(invitations.findByUserIdAndAcceptedAtIsNullAndSupersededAtIsNull(employee.getId()))
                        .extracting(EmployeeInvitation::getId)
                        .containsExactlyInAnyOrder(invitation.getId(), second.getId());
                assertRowLocked("employee_invitations", invitation.getId());
                assertRowLocked("employee_invitations", second.getId());
            });
            assertRowUnlocked("employee_invitations", invitation.getId());
            assertRowUnlocked("employee_invitations", second.getId());
        });
    }

    private void assertRowLocked(String table, UUID id) {
        // Otra conexion real: NOWAIT debe fallar con lock_not_available, no solo devolver una fila.
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "select id from " + table + " where id = ? for update nowait")) {
                statement.setObject(1, id);
                assertThatThrownBy(statement::executeQuery).isInstanceOf(SQLException.class)
                        .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("55P03");
            } finally {
                connection.rollback();
            }
        } catch (SQLException exception) {
            throw new AssertionError("No se pudo comprobar el bloqueo PostgreSQL", exception);
        }
    }

    private void assertRowUnlocked(String table, UUID id) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "select id from " + table + " where id = ? for update nowait")) {
                statement.setObject(1, id);
                try (ResultSet result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private void withCommittedFixture(LockAssertion assertion) throws SQLException {
        User employee = newTransaction().execute(
                status -> persistUser(persistTenant().getId(), UserType.employee));
        try {
            EmployeeInvitation invitation = newTransaction().execute(status -> persistInvitation(employee, NOW));
            assertion.verify(employee, invitation);
        } finally {
            newTransaction().executeWithoutResult(status -> {
                jdbc.update("delete from users where id = ?", employee.getId());
                jdbc.update("delete from tenants where id = ?", employee.getTenantId());
            });
        }
    }

    private TransactionTemplate newTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }

    private EmployeeInvitation persistInvitation(User employee, Instant createdAt) {
        return invitations.saveAndFlush(newInvitation(employee, createdAt));
    }

    private EmployeeInvitation newInvitation(User employee, Instant createdAt) {
        return EmployeeInvitation.builder().userId(employee.getId()).tenantId(employee.getTenantId())
                .tokenHash(tokenHash()).createdAt(createdAt).expiresAt(createdAt.plusSeconds(48 * 60 * 60)).build();
    }

    private String tokenHash() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    private AuthAccount persistAccount(User user) {
        return accounts.saveAndFlush(AuthAccount.builder().userId(user.getId()).email(user.getEmail())
                .passwordHash("repository-test-placeholder-hash").build());
    }

    private User persistUser(UUID tenantId, UserType type) {
        User user = User.builder().name("Repository user").email(UUID.randomUUID() + "@example.test")
                .type(type).build();
        user.setTenantId(tenantId);
        return users.saveAndFlush(user);
    }

    private Tenant persistTenant() {
        return tenants.saveAndFlush(Tenant.builder().name("Repository tenant").slug("repo-" + UUID.randomUUID())
                .status(TenantStatus.active).defaultCurrency("GTQ").timezone("UTC").build());
    }

    @FunctionalInterface
    private interface LockAssertion {
        void verify(User employee, EmployeeInvitation invitation) throws SQLException;
    }
}

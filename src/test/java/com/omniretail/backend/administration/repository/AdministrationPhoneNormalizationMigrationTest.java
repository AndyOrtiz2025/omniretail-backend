package com.omniretail.backend.administration.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.omniretail.backend.TestcontainersConfiguration;
import java.util.Map;
import java.util.UUID;
import liquibase.change.core.RawSQLChange;
import liquibase.changelog.ChangeLogParameters;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class AdministrationPhoneNormalizationMigrationTest {

    private static final String CHANGELOG =
            "db/changelog/changes/029-administration-phone-normalization.yaml";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void normalizesEligiblePhonesIdempotentlyAndLeavesInvalidValuesUntouched() throws Exception {
        UUID tenantId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO tenants (id, name, slug) VALUES (?, ?, ?)",
                tenantId,
                "Tenant teléfonos",
                "phones-" + tenantId);
        insertBranch(tenantId, "DIGITS", "23231232");
        insertBranch(tenantId, "INVALID", "+503 2323-1232");
        jdbc.update(
                "INSERT INTO users (tenant_id, name, email, phone, type) VALUES (?, ?, ?, ?, 'employee')",
                tenantId,
                "Empleado teléfono",
                "phone-" + tenantId + "@example.com",
                "2323-1232");
        jdbc.update(
                "INSERT INTO users (tenant_id, name, email, phone, type) VALUES (?, ?, ?, ?, 'customer')",
                tenantId,
                "Cliente teléfono",
                "customer-" + tenantId + "@example.com",
                "23231232");
        jdbc.update(
                "INSERT INTO suppliers (tenant_id, name, phone) VALUES (?, ?, ?)",
                tenantId,
                "Proveedor teléfono",
                "502 2323-1232");

        executeChangeSet();
        executeChangeSet();

        assertThat(phones("branches", tenantId))
                .containsEntry("DIGITS", "+502 2323-1232")
                .containsEntry("INVALID", "+503 2323-1232");
        assertThat(userPhone(tenantId, "employee")).isEqualTo("+502 2323-1232");
        assertThat(userPhone(tenantId, "customer")).isEqualTo("23231232");
        assertThat(phone("suppliers", tenantId)).isEqualTo("+502 2323-1232");
    }

    private String userPhone(UUID tenantId, String type) {
        return jdbc.queryForObject(
                "SELECT phone FROM users WHERE tenant_id = ? AND type = ?", String.class, tenantId, type);
    }

    private void insertBranch(UUID tenantId, String code, String phone) {
        jdbc.update(
                "INSERT INTO branches (tenant_id, code, name, phone) VALUES (?, ?, ?, ?)",
                tenantId,
                code,
                "Sucursal " + code,
                phone);
    }

    private Map<String, String> phones(String table, UUID tenantId) {
        return jdbc.query(
                "SELECT code, phone FROM " + table + " WHERE tenant_id = ?",
                resultSet -> {
                    Map<String, String> values = new java.util.HashMap<>();
                    while (resultSet.next()) {
                        values.put(resultSet.getString("code"), resultSet.getString("phone"));
                    }
                    return values;
                },
                tenantId);
    }

    private String phone(String table, UUID tenantId) {
        return jdbc.queryForObject(
                "SELECT phone FROM " + table + " WHERE tenant_id = ?", String.class, tenantId);
    }

    private void executeChangeSet() throws Exception {
        try (ClassLoaderResourceAccessor resources = new ClassLoaderResourceAccessor()) {
            var changelog = ChangeLogParserFactory.getInstance()
                    .getParser(CHANGELOG, resources)
                    .parse(CHANGELOG, new ChangeLogParameters(), resources);
            var changeSet = changelog.getChangeSets().stream().findFirst().orElseThrow();
            for (var change : changeSet.getChanges()) {
                assertThat(change).isInstanceOf(RawSQLChange.class);
                jdbc.execute(((RawSQLChange) change).getSql());
            }
        }
    }
}

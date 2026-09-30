package com.omniretail.backend.administration.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.omniretail.backend.TestcontainersConfiguration;
import java.util.Arrays;
import java.util.List;
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
class DemoSubscriptionSeedIntegrationTest {

    private static final UUID DEMO_TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String SEED = "db/changelog/changes/999-04-seed-subscriptions.yaml";
    private static final String INITIAL_SEED = "999-04-seed-subscriptions";
    private static final String DEMO_ADDONS = "999-04-seed-demo-subscription-addons";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void freshDemoGetsBothAddonsWhileOtherTenantsKeepBasicOnly() throws Exception {
        createTenant(DEMO_TENANT_ID);
        UUID otherTenantId = UUID.randomUUID();
        createTenant(otherTenantId);

        executeSeedChangeSet(INITIAL_SEED);
        executeSeedChangeSet(DEMO_ADDONS);

        assertThat(addons(DEMO_TENANT_ID))
                .containsExactly("ecommerce_delivery", "advanced_reports");
        assertThat(addons(otherTenantId)).isEmpty();
    }

    @Test
    void correctiveChangeSetUpdatesAlreadySeededDemoWithoutReplacingSubscription() throws Exception {
        createTenant(DEMO_TENANT_ID);
        UUID otherTenantId = UUID.randomUUID();
        createTenant(otherTenantId);
        executeSeedChangeSet(INITIAL_SEED);
        UUID originalSubscriptionId = jdbc.queryForObject(
                "SELECT id FROM tenant_subscriptions WHERE tenant_id = ?",
                UUID.class,
                DEMO_TENANT_ID);
        jdbc.update("UPDATE tenant_subscriptions SET status = 'suspended' WHERE tenant_id = ?",
                DEMO_TENANT_ID);
        jdbc.update("UPDATE tenant_subscriptions SET addon_codes = ARRAY['advanced_reports']::text[] "
                + "WHERE tenant_id = ?", otherTenantId);

        executeSeedChangeSet(DEMO_ADDONS);
        executeSeedChangeSet(DEMO_ADDONS);

        assertThat(addons(DEMO_TENANT_ID))
                .containsExactly("ecommerce_delivery", "advanced_reports");
        assertThat(addons(otherTenantId)).containsExactly("advanced_reports");
        assertThat(jdbc.queryForList(
                "SELECT id FROM tenant_subscriptions WHERE tenant_id = ?",
                UUID.class,
                DEMO_TENANT_ID)).containsExactly(originalSubscriptionId);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM tenant_subscriptions WHERE tenant_id = ?",
                String.class,
                DEMO_TENANT_ID)).isEqualTo("suspended");
    }

    private void createTenant(UUID tenantId) {
        jdbc.update("INSERT INTO tenants (id, name, slug) VALUES (?, ?, ?)",
                tenantId, "Seed tenant " + tenantId, "seed-" + tenantId);
    }

    private List<String> addons(UUID tenantId) {
        return jdbc.queryForObject(
                "SELECT addon_codes FROM tenant_subscriptions WHERE tenant_id = ?",
                (resultSet, rowNumber) -> Arrays.asList((String[]) resultSet.getArray("addon_codes").getArray()),
                tenantId);
    }

    private void executeSeedChangeSet(String changeSetId) throws Exception {
        try (ClassLoaderResourceAccessor resources = new ClassLoaderResourceAccessor()) {
            var changelog = ChangeLogParserFactory.getInstance().getParser(SEED, resources)
                    .parse(SEED, new ChangeLogParameters(), resources);
            var changeSet = changelog.getChangeSets().stream()
                    .filter(candidate -> changeSetId.equals(candidate.getId()))
                    .findFirst().orElseThrow();
            for (var change : changeSet.getChanges()) {
                assertThat(change).isInstanceOf(RawSQLChange.class);
                jdbc.execute(((RawSQLChange) change).getSql());
            }
        }
    }
}

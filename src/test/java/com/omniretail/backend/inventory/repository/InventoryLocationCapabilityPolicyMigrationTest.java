package com.omniretail.backend.inventory.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.filter.ContextChangeSetFilter;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class InventoryLocationCapabilityPolicyMigrationTest {

    private static final String MASTER_CHANGELOG = "db/changelog/db.changelog-master.yaml";
    private static final String CHANGESET_ID = "062-inventory-location-capability-policy";
    private static final Contexts TEST_CONTEXTS = new Contexts("test");
    private static final LabelExpression LABELS = new LabelExpression();

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void compatibleUpgradeNormalizesTheAliasWithoutChangingLegacyBusinessData() throws Exception {
        try (MigrationHarness migration = MigrationHarness.before062()) {
            Fixture fixture = fixture(migration.jdbc(), true, false);
            UUID balanceId = UUID.randomUUID();
            UUID movementId = UUID.randomUUID();
            migration.jdbc().update(
                    "INSERT INTO inventory_balances "
                            + "(id, tenant_id, branch_id, product_id, location_id, quantity, reserved_quantity) "
                            + "VALUES (?, ?, ?, ?, NULL, 30.000, 2.000)",
                    balanceId, fixture.tenantId(), fixture.branchId(), fixture.productId());
            migration.jdbc().update(
                    "INSERT INTO inventory_movements "
                            + "(id, tenant_id, branch_id, product_id, type, reason, quantity, quantity_before, "
                            + "quantity_after, performed_by_user_id) "
                            + "VALUES (?, ?, ?, ?, 'in', 'Movimiento anterior a 062', 30.000, 0.000, 30.000, ?)",
                    movementId,
                    fixture.tenantId(),
                    fixture.branchId(),
                    fixture.productId(),
                    fixture.userId());

            migration.upgrade();

            assertThat(migration.changeSetExecutions()).isEqualTo(1);
            assertThat(migration.constraintExists()).isTrue();
            assertThat(migration.multipleLocationsDefault()).isEqualTo("true");
            assertThat(migration.capabilities(fixture.tenantId())).containsExactly(true, true);
            assertThat(migration.jdbc().queryForObject(
                            "SELECT location_id FROM inventory_balances WHERE id = ?",
                            UUID.class,
                            balanceId))
                    .isNull();
            assertThat(decimal(migration.jdbc(),
                            "SELECT quantity FROM inventory_balances WHERE id = ?", balanceId))
                    .isEqualByComparingTo("30.000");
            assertThat(decimal(migration.jdbc(),
                            "SELECT reserved_quantity FROM inventory_balances WHERE id = ?", balanceId))
                    .isEqualByComparingTo("2.000");
            assertThat(migration.jdbc().queryForObject(
                            "SELECT reason FROM inventory_movements WHERE id = ?", String.class, movementId))
                    .isEqualTo("Movimiento anterior a 062");
            assertThat(decimal(migration.jdbc(),
                            "SELECT quantity FROM inventory_movements WHERE id = ?", movementId))
                    .isEqualByComparingTo("30.000");
            assertThat(migration.jdbc().queryForObject(
                            "SELECT name FROM products WHERE id = ?", String.class, fixture.productId()))
                    .isEqualTo("Producto upgrade");
            assertThat(migration.jdbc().queryForObject(
                            "SELECT email FROM users WHERE id = ?", String.class, fixture.userId()))
                    .isEqualTo("upgrade@example.test");
            assertThatThrownBy(() -> migration.jdbc().update(
                            "UPDATE business_capabilities_configs "
                                    + "SET supports_multiple_locations = false WHERE tenant_id = ?",
                            fixture.tenantId()))
                    .hasMessageContaining("chk_business_config_inventory_locations");
            assertThat(migration.capabilities(fixture.tenantId())).containsExactly(true, true);
        }
    }

    @Test
    void incompatibleLocatedBalanceHaltsBeforeChangingConfigurationOrInventory() throws Exception {
        try (MigrationHarness migration = MigrationHarness.before062()) {
            Fixture fixture = fixture(migration.jdbc(), true, false);
            UUID locationId = location(migration.jdbc(), fixture);
            UUID balanceId = UUID.randomUUID();
            migration.jdbc().update(
                    "INSERT INTO inventory_balances "
                            + "(id, tenant_id, branch_id, product_id, location_id, quantity, reserved_quantity) "
                            + "VALUES (?, ?, ?, ?, ?, 7.000, 2.000)",
                    balanceId,
                    fixture.tenantId(),
                    fixture.branchId(),
                    fixture.productId(),
                    locationId);

            assertUpgradeHalts(migration);

            assertThat(migration.capabilities(fixture.tenantId())).containsExactly(true, false);
            assertThat(decimal(migration.jdbc(),
                            "SELECT quantity FROM inventory_balances WHERE id = ?", balanceId))
                    .isEqualByComparingTo("7.000");
            assertThat(decimal(migration.jdbc(),
                            "SELECT reserved_quantity FROM inventory_balances WHERE id = ?", balanceId))
                    .isEqualByComparingTo("2.000");
            assertThat(migration.jdbc().queryForObject(
                            "SELECT location_id FROM inventory_balances WHERE id = ?", UUID.class, balanceId))
                    .isEqualTo(locationId);
        }
    }

    @Test
    void incompatibleLotBalanceAlsoHaltsTheUpgrade() throws Exception {
        try (MigrationHarness migration = MigrationHarness.before062()) {
            Fixture fixture = fixture(migration.jdbc(), true, false);
            UUID locationId = location(migration.jdbc(), fixture);
            UUID lotId = UUID.randomUUID();
            UUID lotBalanceId = UUID.randomUUID();
            migration.jdbc().update(
                    "INSERT INTO inventory_lots (id, tenant_id, product_id, lot_number) VALUES (?, ?, ?, ?)",
                    lotId, fixture.tenantId(), fixture.productId(), "LOT-062");
            migration.jdbc().update(
                    "INSERT INTO inventory_lot_balances "
                            + "(id, tenant_id, branch_id, location_id, lot_id, quantity, reserved_quantity) "
                            + "VALUES (?, ?, ?, ?, ?, 4.000, 1.000)",
                    lotBalanceId, fixture.tenantId(), fixture.branchId(), locationId, lotId);

            assertUpgradeHalts(migration);

            assertThat(migration.capabilities(fixture.tenantId())).containsExactly(true, false);
            assertThat(decimal(migration.jdbc(),
                            "SELECT quantity FROM inventory_lot_balances WHERE id = ?", lotBalanceId))
                    .isEqualByComparingTo("4.000");
            assertThat(decimal(migration.jdbc(),
                            "SELECT reserved_quantity FROM inventory_lot_balances WHERE id = ?", lotBalanceId))
                    .isEqualByComparingTo("1.000");
        }
    }

    @Test
    void incompatibleAvailableSerialAlsoHaltsTheUpgrade() throws Exception {
        try (MigrationHarness migration = MigrationHarness.before062()) {
            Fixture fixture = fixture(migration.jdbc(), true, false);
            UUID locationId = location(migration.jdbc(), fixture);
            UUID serialId = UUID.randomUUID();
            migration.jdbc().update(
                    "INSERT INTO inventory_serials "
                            + "(id, tenant_id, branch_id, location_id, product_id, serial_number, status) "
                            + "VALUES (?, ?, ?, ?, ?, 'SERIAL-062', 'AVAILABLE')",
                    serialId,
                    fixture.tenantId(),
                    fixture.branchId(),
                    locationId,
                    fixture.productId());

            assertUpgradeHalts(migration);

            assertThat(migration.capabilities(fixture.tenantId())).containsExactly(true, false);
            assertThat(migration.jdbc().queryForObject(
                            "SELECT status FROM inventory_serials WHERE id = ?", String.class, serialId))
                    .isEqualTo("AVAILABLE");
            assertThat(migration.jdbc().queryForObject(
                            "SELECT location_id FROM inventory_serials WHERE id = ?", UUID.class, serialId))
                    .isEqualTo(locationId);
        }
    }

    @Test
    void coherentUpgradeIsOperationallyIdempotentAndIsRecordedOnlyOnce() throws Exception {
        try (MigrationHarness migration = MigrationHarness.before062()) {
            Fixture fixture = fixture(migration.jdbc(), true, true);
            UUID locationId = location(migration.jdbc(), fixture);
            UUID balanceId = UUID.randomUUID();
            migration.jdbc().update(
                    "INSERT INTO product_inventory_settings "
                            + "(tenant_id, branch_id, product_id, min_stock, reorder_point, default_location_id) "
                            + "VALUES (?, ?, ?, 3.000, 8.000, ?)",
                    fixture.tenantId(), fixture.branchId(), fixture.productId(), locationId);
            migration.jdbc().update(
                    "INSERT INTO inventory_balances "
                            + "(id, tenant_id, branch_id, product_id, location_id, quantity, reserved_quantity) "
                            + "VALUES (?, ?, ?, ?, ?, 11.000, 3.000)",
                    balanceId,
                    fixture.tenantId(),
                    fixture.branchId(),
                    fixture.productId(),
                    locationId);
            OffsetDateTime updatedAt = migration.jdbc().queryForObject(
                    "SELECT updated_at FROM business_capabilities_configs WHERE tenant_id = ?",
                    OffsetDateTime.class,
                    fixture.tenantId());

            migration.upgrade();
            migration.upgrade();

            assertThat(migration.changeSetExecutions()).isEqualTo(1);
            assertThat(migration.capabilities(fixture.tenantId())).containsExactly(true, true);
            assertThat(migration.jdbc().queryForObject(
                            "SELECT updated_at FROM business_capabilities_configs WHERE tenant_id = ?",
                            OffsetDateTime.class,
                            fixture.tenantId()))
                    .isEqualTo(updatedAt);
            assertThat(decimal(migration.jdbc(),
                            "SELECT quantity FROM inventory_balances WHERE id = ?", balanceId))
                    .isEqualByComparingTo("11.000");
            assertThat(decimal(migration.jdbc(),
                            "SELECT reserved_quantity FROM inventory_balances WHERE id = ?", balanceId))
                    .isEqualByComparingTo("3.000");
            assertThat(migration.jdbc().queryForObject(
                            "SELECT default_location_id FROM product_inventory_settings "
                                    + "WHERE tenant_id = ? AND branch_id = ? AND product_id = ?",
                            UUID.class,
                            fixture.tenantId(),
                            fixture.branchId(),
                            fixture.productId()))
                    .isEqualTo(locationId);
        }
    }

    private static void assertUpgradeHalts(MigrationHarness migration) {
        assertThatThrownBy(migration::upgrade)
                .isInstanceOf(LiquibaseException.class)
                .satisfies(error -> assertThat(error.getMessage().toLowerCase(Locale.ROOT))
                        .contains("precondition"));
        assertThat(migration.changeSetExecutions()).isZero();
        assertThat(migration.constraintExists()).isFalse();
        assertThat(migration.multipleLocationsDefault()).isEqualTo("false");
    }

    private static Fixture fixture(JdbcTemplate jdbc, boolean inventory, boolean multipleLocations) {
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO tenants (id, name, slug) VALUES (?, 'Tenant upgrade', ?)",
                tenantId, "upgrade-" + tenantId);
        jdbc.update(
                "INSERT INTO branches (id, tenant_id, code, name, type, status) "
                        + "VALUES (?, ?, 'MAIN', 'Principal', 'main', 'active')",
                branchId, tenantId);
        jdbc.update(
                "INSERT INTO categories (id, tenant_id, name, slug, status) "
                        + "VALUES (?, ?, 'Categoria upgrade', ?, 'active')",
                categoryId, tenantId, "category-" + categoryId);
        jdbc.update(
                "INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status) "
                        + "VALUES (?, ?, ?, 'Unidad', 'und', 'unit', true, 'active')",
                unitId, tenantId, "U-" + unitId.toString().substring(0, 8));
        jdbc.update(
                "INSERT INTO products "
                        + "(id, tenant_id, sku, name, category_id, base_unit_id, product_type, tracking_stock) "
                        + "VALUES (?, ?, ?, 'Producto upgrade', ?, ?, 'physical', true)",
                productId, tenantId, "SKU-" + productId, categoryId, unitId);
        jdbc.update(
                "INSERT INTO users (id, tenant_id, name, email, type, status) "
                        + "VALUES (?, ?, 'Usuario upgrade', 'upgrade@example.test', 'employee', 'active')",
                userId, tenantId);
        jdbc.update(
                "INSERT INTO business_capabilities_configs "
                        + "(tenant_id, preset, supports_inventory, supports_multiple_locations) "
                        + "VALUES (?, 'custom', ?, ?)",
                tenantId, inventory, multipleLocations);
        return new Fixture(tenantId, branchId, productId, userId);
    }

    private static UUID location(JdbcTemplate jdbc, Fixture fixture) {
        UUID locationId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status) "
                        + "VALUES (?, ?, ?, ?, 'Bodega upgrade', 'warehouse', 'active')",
                locationId,
                fixture.tenantId(),
                fixture.branchId(),
                "LOC-" + locationId.toString().substring(0, 8));
        return locationId;
    }

    private static BigDecimal decimal(JdbcTemplate jdbc, String sql, Object... arguments) {
        return jdbc.queryForObject(sql, BigDecimal.class, arguments);
    }

    private record Fixture(UUID tenantId, UUID branchId, UUID productId, UUID userId) {}

    private static final class MigrationHarness implements AutoCloseable {

        private final String schema;
        private final JdbcTemplate jdbc;
        private final Connection connection;
        private final ClassLoaderResourceAccessor resources;
        private final Liquibase liquibase;

        private MigrationHarness(
                String schema,
                JdbcTemplate jdbc,
                Connection connection,
                ClassLoaderResourceAccessor resources,
                Liquibase liquibase) {
            this.schema = schema;
            this.jdbc = jdbc;
            this.connection = connection;
            this.resources = resources;
            this.liquibase = liquibase;
        }

        static MigrationHarness before062() throws Exception {
            String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
            try (Connection admin = DriverManager.getConnection(
                            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                    var statement = admin.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
            }
            String separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
            String schemaUrl = POSTGRES.getJdbcUrl() + separator + "currentSchema=" + schema;
            DriverManagerDataSource dataSource =
                    new DriverManagerDataSource(schemaUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            Connection connection = dataSource.getConnection();
            ClassLoaderResourceAccessor resources = new ClassLoaderResourceAccessor();
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            database.setDefaultSchemaName(schema);
            Liquibase liquibase = new Liquibase(MASTER_CHANGELOG, resources, database);
            int changeSetIndex = -1;
            var changeSets = liquibase.getDatabaseChangeLog().getChangeSets();
            for (int index = 0; index < changeSets.size(); index++) {
                if (CHANGESET_ID.equals(changeSets.get(index).getId())) {
                    changeSetIndex = index;
                    break;
                }
            }
            if (changeSetIndex < 0) {
                liquibase.close();
                resources.close();
                throw new IllegalStateException("No se encontro el changeset " + CHANGESET_ID);
            }
            ContextChangeSetFilter contextFilter = new ContextChangeSetFilter(TEST_CONTEXTS);
            int runnableBefore062 = (int) changeSets.subList(0, changeSetIndex).stream()
                    .filter(changeSet -> contextFilter.accepts(changeSet).isAccepted())
                    .count();
            liquibase.update(runnableBefore062, TEST_CONTEXTS, LABELS);
            MigrationHarness harness = new MigrationHarness(schema, jdbc, connection, resources, liquibase);
            assertThat(harness.changeSetExecutions()).isZero();
            return harness;
        }

        JdbcTemplate jdbc() {
            return jdbc;
        }

        void upgrade() throws LiquibaseException {
            liquibase.update(TEST_CONTEXTS, LABELS);
        }

        int changeSetExecutions() {
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM databasechangelog WHERE id = ?", Integer.class, CHANGESET_ID);
        }

        boolean constraintExists() {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pg_constraint "
                            + "WHERE conname = 'chk_business_config_inventory_locations' "
                            + "AND conrelid = 'business_capabilities_configs'::regclass",
                    Integer.class);
            return count != null && count == 1;
        }

        String multipleLocationsDefault() {
            return jdbc.queryForObject(
                    "SELECT column_default FROM information_schema.columns "
                            + "WHERE table_schema = ? AND table_name = 'business_capabilities_configs' "
                            + "AND column_name = 'supports_multiple_locations'",
                    String.class,
                    schema);
        }

        Boolean[] capabilities(UUID tenantId) {
            return jdbc.queryForObject(
                    "SELECT supports_inventory, supports_multiple_locations "
                            + "FROM business_capabilities_configs WHERE tenant_id = ?",
                    (resultSet, rowNumber) -> new Boolean[] {
                        resultSet.getBoolean(1), resultSet.getBoolean(2)
                    },
                    tenantId);
        }

        @Override
        public void close() throws Exception {
            try {
                liquibase.close();
            } finally {
                resources.close();
                if (!connection.isClosed()) {
                    connection.close();
                }
                try (Connection admin = DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                        var statement = admin.createStatement()) {
                    statement.execute("DROP SCHEMA " + schema + " CASCADE");
                }
            }
        }
    }
}

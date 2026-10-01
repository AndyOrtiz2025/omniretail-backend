package com.omniretail.backend.logistics.entity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Version;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PackingJpaMappingTest {

    @Test
    void usesTheCanonicalFrontendEnums() {
        assertThat(names(PackingSourceType.values())).containsExactly("order", "transfer");
        assertThat(names(PackingStatus.values())).containsExactly("in_progress", "finalized");
        assertThat(names(PackingOperationType.values()))
                .containsExactly(
                        "save_preparation",
                        "generate_label",
                        "register_label_print",
                        "finalize");
    }

    @Test
    void mapsSourcePickingAndChecklistColumns() throws Exception {
        assertColumn("branchId", "branch_id", false, true);
        assertColumn("sourceType", "source_type", false, true);
        assertColumn("sourceId", "source_id", false, true);
        assertColumn("orderId", "order_id", true, true);
        assertColumn("pickingOrderId", "picking_order_id", false, true);
        assertColumn("packageProtectionChecked", "package_protection_checked", false, false);
        assertColumn("documentIncludedChecked", "document_included_checked", false, false);
        assertColumn("recipientVerifiedChecked", "recipient_verified_checked", false, false);

        assertStringEnum("sourceType");
        assertStringEnum("status");
    }

    @Test
    void mapsWeightAndOptimisticVersion() throws Exception {
        Column weight = Packing.class.getDeclaredField("totalWeight").getAnnotation(Column.class);
        assertThat(weight.precision()).isEqualTo(12);
        assertThat(weight.scale()).isEqualTo(3);

        Field version = Packing.class.getDeclaredField("version");
        assertThat(version.getAnnotation(Version.class)).isNotNull();
        assertThat(version.getType()).isEqualTo(Long.class);
        assertThat(version.getAnnotation(Column.class).nullable()).isFalse();
    }

    private static String[] names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toArray(String[]::new);
    }

    private static void assertStringEnum(String fieldName) throws Exception {
        Enumerated enumerated =
                Packing.class.getDeclaredField(fieldName).getAnnotation(Enumerated.class);
        assertThat(enumerated).isNotNull();
        assertThat(enumerated.value()).isEqualTo(EnumType.STRING);
    }

    private static void assertColumn(
            String fieldName, String columnName, boolean nullable, boolean immutable)
            throws Exception {
        Column column = Packing.class.getDeclaredField(fieldName).getAnnotation(Column.class);
        assertThat(column.name()).isEqualTo(columnName);
        assertThat(column.nullable()).isEqualTo(nullable);
        assertThat(column.updatable()).isEqualTo(!immutable);
    }
}

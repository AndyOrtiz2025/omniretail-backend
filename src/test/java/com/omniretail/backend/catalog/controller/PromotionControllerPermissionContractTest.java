package com.omniretail.backend.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.omniretail.backend.catalog.dto.CreatePromotionRequest;
import com.omniretail.backend.catalog.dto.UpdatePromotionRequest;
import com.omniretail.backend.shared.security.RequirePermission;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class PromotionControllerPermissionContractTest {

    @Test
    void endpointsKeepExactPathsAndPermissionStrings() throws Exception {
        assertThat(PromotionController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/catalog/promotions");

        Method list = PromotionController.class.getMethod("list", UUID.class, Pageable.class);
        Method detail = PromotionController.class.getMethod("get", UUID.class);
        Method create = PromotionController.class.getMethod("create", CreatePromotionRequest.class);
        Method update = PromotionController.class.getMethod("update", UUID.class, UpdatePromotionRequest.class);
        Method end = PromotionController.class.getMethod("end", UUID.class);
        Method cancel = PromotionController.class.getMethod("cancel", UUID.class);

        assertPermission(list, "catalog.promotions.read");
        assertPermission(detail, "catalog.promotions.read");
        assertPermission(create, "catalog.promotions.manage");
        assertPermission(update, "catalog.promotions.manage");
        assertPermission(end, "catalog.promotions.manage");
        assertPermission(cancel, "catalog.promotions.manage");
        assertThat(list.getAnnotation(GetMapping.class).value()).isEmpty();
        assertThat(detail.getAnnotation(GetMapping.class).value()).containsExactly("/{id}");
        assertThat(create.getAnnotation(PostMapping.class).value()).isEmpty();
        assertThat(update.getAnnotation(PutMapping.class).value()).containsExactly("/{id}");
        assertThat(end.getAnnotation(PutMapping.class).value()).containsExactly("/{id}/end");
        assertThat(cancel.getAnnotation(PutMapping.class).value()).containsExactly("/{id}/cancel");
    }

    private static void assertPermission(Method method, String expected) {
        RequirePermission permission = method.getAnnotation(RequirePermission.class);
        assertThat(permission).isNotNull();
        assertThat(permission.value()).isEqualTo(expected);
    }
}

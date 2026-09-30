package com.omniretail.backend.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import com.omniretail.backend.administration.dto.BusinessConfigResponse;
import com.omniretail.backend.administration.entity.BusinessPreset;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BusinessConfigService;
import com.omniretail.backend.catalog.dto.AttributeDefinitionCreateRequest;
import com.omniretail.backend.catalog.dto.AttributeDefinitionUpdateRequest;
import com.omniretail.backend.catalog.entity.AttributeDataType;
import com.omniretail.backend.catalog.entity.AttributeDefinition;
import com.omniretail.backend.catalog.entity.AttributeDefinitionStatus;
import com.omniretail.backend.catalog.repository.AttributeDefinitionRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class AttributeDefinitionServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ID = UUID.randomUUID();

    @Mock private AttributeDefinitionRepository repository;
    @Mock private BusinessConfigService businessConfigService;
    @Mock private CurrentUser currentUser;

    private AttributeDefinitionService service;

    @BeforeEach
    void setUp() {
        service = new AttributeDefinitionService(repository, businessConfigService, currentUser);
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                UUID.randomUUID(), TENANT, UserType.employee, null, null, UUID.randomUUID()));
    }

    @Test
    void createNormalizesCodeAndNameAndStartsActive() {
        given(businessConfigService.getConfig()).willReturn(config(true));
        given(repository.saveAndFlush(any())).willAnswer(invocation -> invocation.getArgument(0));

        service.create(new AttributeDefinitionCreateRequest("  CoLoR  ", "  Color comercial  ", AttributeDataType.TEXT));

        ArgumentCaptor<AttributeDefinition> captor = ArgumentCaptor.forClass(AttributeDefinition.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT);
        assertThat(captor.getValue().getCode()).isEqualTo("color");
        assertThat(captor.getValue().getName()).isEqualTo("Color comercial");
        assertThat(captor.getValue().getStatus()).isEqualTo(AttributeDefinitionStatus.active);
    }

    @Test
    void writesRequireBusinessCapabilityButReadsDoNot() {
        given(businessConfigService.getConfig()).willReturn(config(false));
        assertCode(
                () -> service.create(new AttributeDefinitionCreateRequest("color", "Color", AttributeDataType.TEXT)),
                "PRODUCT_CAPABILITY_DISABLED");
    }

    @Test
    void listDoesNotRequireAttributesCapability() {
        PageRequest pageable = PageRequest.of(0, 20);
        given(repository.findByTenantId(TENANT, pageable))
                .willReturn(new PageImpl<>(List.of(definition(AttributeDefinitionStatus.archived))));

        assertThat(service.list(pageable).items()).hasSize(1);

        verify(businessConfigService, never()).getConfig();
    }

    @Test
    void duplicateCodeAndCrossTenantIdsUseStableErrors() {
        given(businessConfigService.getConfig()).willReturn(config(true));
        given(repository.existsByTenantIdAndCodeIgnoreCase(TENANT, "color")).willReturn(true);
        assertCode(
                () -> service.create(new AttributeDefinitionCreateRequest(" COLOR ", "Color", AttributeDataType.TEXT)),
                "ATTRIBUTE_CODE_CONFLICT");

        UUID foreign = UUID.randomUUID();
        given(repository.findByTenantIdAndId(TENANT, foreign)).willReturn(Optional.empty());
        assertCode(
                () -> service.update(foreign, new AttributeDefinitionUpdateRequest("Nombre")),
                "ATTRIBUTE_DEFINITION_NOT_FOUND");
    }

    @Test
    void databaseDuplicateRaceIsTranslatedWithoutARecoveryQuery() {
        given(businessConfigService.getConfig()).willReturn(config(true));
        given(repository.saveAndFlush(any())).willThrow(new DataIntegrityViolationException(
                "duplicate key violates uk_attribute_definitions_tenant_code_ci"));

        assertCode(
                () -> service.create(new AttributeDefinitionCreateRequest(
                        "color", "Color", AttributeDataType.TEXT)),
                "ATTRIBUTE_CODE_CONFLICT");
    }

    @Test
    void updateOnlyChangesNameAndArchiveIsIdempotent() {
        given(businessConfigService.getConfig()).willReturn(config(true));
        AttributeDefinition definition = definition(AttributeDefinitionStatus.active);
        given(repository.findByTenantIdAndId(TENANT, ID)).willReturn(Optional.of(definition));
        given(repository.saveAndFlush(definition)).willReturn(definition);

        service.update(ID, new AttributeDefinitionUpdateRequest("  Nuevo nombre "));
        assertThat(definition.getName()).isEqualTo("Nuevo nombre");
        assertThat(definition.getCode()).isEqualTo("color");
        assertThat(definition.getDataType()).isEqualTo(AttributeDataType.TEXT);

        service.archive(ID);
        assertThat(definition.getStatus()).isEqualTo(AttributeDefinitionStatus.archived);
        service.archive(ID);
    }

    private static AttributeDefinition definition(AttributeDefinitionStatus status) {
        AttributeDefinition definition = AttributeDefinition.builder()
                .code("color")
                .name("Color")
                .dataType(AttributeDataType.TEXT)
                .status(status)
                .build();
        definition.setTenantId(TENANT);
        ReflectionTestUtils.setField(definition, "id", ID);
        return definition;
    }

    private static BusinessConfigResponse config(boolean attributes) {
        return new BusinessConfigResponse(
                TENANT, BusinessPreset.custom, true, true, true, true, true, true,
                attributes, true, true, List.of(),
                new com.omniretail.backend.administration.dto.ProductTrackingDto(true, true, true, true));
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(code));
    }
}

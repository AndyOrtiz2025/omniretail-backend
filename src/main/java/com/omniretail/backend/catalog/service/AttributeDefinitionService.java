package com.omniretail.backend.catalog.service;

import com.omniretail.backend.administration.dto.BusinessConfigResponse;
import com.omniretail.backend.administration.service.BusinessConfigService;
import com.omniretail.backend.catalog.dto.AttributeDefinitionCreateRequest;
import com.omniretail.backend.catalog.dto.AttributeDefinitionResponse;
import com.omniretail.backend.catalog.dto.AttributeDefinitionUpdateRequest;
import com.omniretail.backend.catalog.entity.AttributeDefinition;
import com.omniretail.backend.catalog.entity.AttributeDefinitionStatus;
import com.omniretail.backend.catalog.repository.AttributeDefinitionRepository;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.CurrentUser;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AttributeDefinitionService {

    private static final String UNIQUE_CODE_CONSTRAINT = "uk_attribute_definitions_tenant_code_ci";

    private final AttributeDefinitionRepository definitionRepository;
    private final BusinessConfigService businessConfigService;
    private final CurrentUser currentUser;

    public PageResponse<AttributeDefinitionResponse> list(Pageable pageable) {
        UUID tenantId = currentUser.require().tenantId();
        return PageResponse.from(
                definitionRepository.findByTenantId(tenantId, pageable),
                AttributeDefinitionResponse::from);
    }

    @Transactional
    public AttributeDefinitionResponse create(AttributeDefinitionCreateRequest request) {
        UUID tenantId = currentUser.require().tenantId();
        requireAttributesCapability();
        String code = normalizeCode(request.code());
        String name = normalizeName(request.name());
        if (request.dataType() == null) {
            throw invalidDefinition("El tipo de dato del atributo es requerido.");
        }
        if (definitionRepository.existsByTenantIdAndCodeIgnoreCase(tenantId, code)) {
            throw codeConflict();
        }

        AttributeDefinition definition = AttributeDefinition.builder()
                .code(code)
                .name(name)
                .dataType(request.dataType())
                .status(AttributeDefinitionStatus.active)
                .build();
        definition.setTenantId(tenantId);
        try {
            return AttributeDefinitionResponse.from(definitionRepository.saveAndFlush(definition));
        } catch (DataIntegrityViolationException exception) {
            if (exceptionDetail(exception).contains(UNIQUE_CODE_CONSTRAINT)) {
                throw codeConflict();
            }
            throw exception;
        }
    }

    @Transactional
    public AttributeDefinitionResponse update(UUID id, AttributeDefinitionUpdateRequest request) {
        UUID tenantId = currentUser.require().tenantId();
        AttributeDefinition definition = requireDefinition(tenantId, id);
        requireAttributesCapability();
        definition.setName(normalizeName(request.name()));
        return AttributeDefinitionResponse.from(definitionRepository.saveAndFlush(definition));
    }

    @Transactional
    public void archive(UUID id) {
        UUID tenantId = currentUser.require().tenantId();
        AttributeDefinition definition = requireDefinition(tenantId, id);
        requireAttributesCapability();
        if (definition.getStatus() == AttributeDefinitionStatus.archived) {
            return;
        }
        definition.setStatus(AttributeDefinitionStatus.archived);
        definitionRepository.saveAndFlush(definition);
    }

    private AttributeDefinition requireDefinition(UUID tenantId, UUID id) {
        return definitionRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(AttributeDefinitionService::definitionNotFound);
    }

    private void requireAttributesCapability() {
        BusinessConfigResponse config = businessConfigService.getConfig();
        if (!config.supportsProductAttributes()) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "PRODUCT_CAPABILITY_DISABLED",
                    "El negocio no tiene habilitados los atributos de producto.");
        }
    }

    private static String normalizeCode(String value) {
        if (value == null) {
            throw invalidDefinition("El codigo del atributo es requerido.");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > 50) {
            throw invalidDefinition("El codigo del atributo debe tener entre 1 y 50 caracteres.");
        }
        return normalized;
    }

    private static String normalizeName(String value) {
        if (value == null) {
            throw invalidDefinition("El nombre del atributo es requerido.");
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 200) {
            throw invalidDefinition("El nombre del atributo debe tener entre 1 y 200 caracteres.");
        }
        return normalized;
    }

    private static BusinessException invalidDefinition(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "ATTRIBUTE_DEFINITION_INVALID", message);
    }

    private static BusinessException definitionNotFound() {
        return new BusinessException(
                HttpStatus.NOT_FOUND, "ATTRIBUTE_DEFINITION_NOT_FOUND", "Atributo no encontrado.");
    }

    private static BusinessException codeConflict() {
        return BusinessException.conflict(
                "ATTRIBUTE_CODE_CONFLICT", "Ya existe un atributo con ese codigo.");
    }

    private static String exceptionDetail(Throwable exception) {
        StringBuilder detail = new StringBuilder();
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                detail.append(' ').append(current.getMessage().toLowerCase(Locale.ROOT));
            }
        }
        return detail.toString();
    }
}

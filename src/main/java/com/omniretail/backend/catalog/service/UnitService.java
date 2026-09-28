package com.omniretail.backend.catalog.service;

import com.omniretail.backend.catalog.dto.UnitCreateRequest;
import com.omniretail.backend.catalog.dto.UnitResponse;
import com.omniretail.backend.catalog.dto.UnitUpdateRequest;
import com.omniretail.backend.catalog.entity.Unit;
import com.omniretail.backend.catalog.entity.UnitStatus;
import com.omniretail.backend.catalog.repository.UnitRepository;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.CurrentUser;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UnitService {

    private static final String UNIT_NOT_FOUND = "Unidad no encontrada.";

    private final UnitRepository unitRepository;
    private final CurrentUser currentUser;

    public PageResponse<UnitResponse> list(UnitStatus status, Pageable pageable) {
        UUID tenantId = currentUser.require().tenantId();
        Page<Unit> units = status == null
                ? unitRepository.findByTenantId(tenantId, pageable)
                : unitRepository.findByTenantIdAndStatus(tenantId, status, pageable);
        return PageResponse.from(units, UnitResponse::from);
    }

    public UnitResponse getById(UUID id) {
        UUID tenantId = currentUser.require().tenantId();
        return UnitResponse.from(requireUnit(tenantId, id));
    }

    @Transactional
    public UnitResponse create(UnitCreateRequest request) {
        UUID tenantId = currentUser.require().tenantId();
        String code = normalizeCode(request.code());
        if (unitRepository.existsByTenantIdAndCode(tenantId, code)) {
            throw BusinessException.conflict(
                    "UNIT_CODE_CONFLICT",
                    "Ya existe una unidad con ese código.");
        }

        Unit unit = Unit.builder()
                .code(code)
                .name(request.name().trim())
                .symbol(request.symbol().trim())
                .category(request.category())
                .allowsDecimals(
                        request.allowsDecimals() != null ? request.allowsDecimals() : false)
                .status(request.status() != null ? request.status() : UnitStatus.active)
                .build();
        unit.setTenantId(tenantId);
        return UnitResponse.from(unitRepository.saveAndFlush(unit));
    }

    @Transactional
    public UnitResponse update(UUID id, UnitUpdateRequest request) {
        UUID tenantId = currentUser.require().tenantId();
        Unit unit = requireUnit(tenantId, id);
        unit.setName(request.name().trim());
        unit.setSymbol(request.symbol().trim());
        unit.setCategory(request.category());
        unit.setAllowsDecimals(request.allowsDecimals());
        unit.setStatus(request.status());
        return UnitResponse.from(unitRepository.saveAndFlush(unit));
    }

    @Transactional
    public void archive(UUID id) {
        UUID tenantId = currentUser.require().tenantId();
        Unit unit = requireUnit(tenantId, id);
        if (unit.getStatus() == UnitStatus.archived) {
            return;
        }
        unit.setStatus(UnitStatus.archived);
        unitRepository.saveAndFlush(unit);
    }

    private Unit requireUnit(UUID tenantId, UUID id) {
        return unitRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "UNIT_NOT_FOUND",
                        UNIT_NOT_FOUND));
    }

    private static String normalizeCode(String value) {
        String code = value.trim().replaceAll("\\s+", "-").toUpperCase(Locale.ROOT);
        if (code.isEmpty() || code.length() > 20) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "UNIT_CODE_INVALID",
                    "El código de la unidad no es válido.");
        }
        return code;
    }
}

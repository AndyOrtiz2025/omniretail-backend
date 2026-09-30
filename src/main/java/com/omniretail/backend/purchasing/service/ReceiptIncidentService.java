package com.omniretail.backend.purchasing.service;

import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.catalog.entity.Unit;
import com.omniretail.backend.catalog.repository.UnitRepository;
import com.omniretail.backend.purchasing.dto.CreateReceiptIncidentRequest;
import com.omniretail.backend.purchasing.dto.ReceiptIncidentResponse;
import com.omniretail.backend.purchasing.entity.GoodsReceipt;
import com.omniretail.backend.purchasing.entity.GoodsReceiptItem;
import com.omniretail.backend.purchasing.entity.ReceiptIncident;
import com.omniretail.backend.purchasing.entity.ReceiptIncidentStatus;
import com.omniretail.backend.purchasing.repository.GoodsReceiptItemRepository;
import com.omniretail.backend.purchasing.repository.GoodsReceiptRepository;
import com.omniretail.backend.purchasing.repository.ReceiptIncidentRepository;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.PermissionResolver;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class ReceiptIncidentService {

    private static final String MANAGE_PERMISSION = "receiving.incidents.manage";
    private static final List<String> READ_PERMISSIONS = List.of(
            "receiving.receipts.read",
            "receiving.receipts.create",
            "receiving.receipts.confirm");

    private final ReceiptIncidentRepository receiptIncidentRepository;
    private final GoodsReceiptRepository goodsReceiptRepository;
    private final GoodsReceiptItemRepository goodsReceiptItemRepository;
    private final UnitRepository unitRepository;
    private final BranchAccessResolver branchAccessResolver;
    private final TenantCapabilityGuard tenantCapabilityGuard;
    private final PermissionResolver permissionResolver;
    private final CurrentUser currentUser;

    @Transactional(readOnly = true)
    public PageResponse<ReceiptIncidentResponse> list(UUID receiptId, Pageable requestedPageable) {
        AuthenticatedUser actor = requireReadActor();
        GoodsReceipt receipt = requireReceipt(actor.tenantId(), receiptId);
        requireBranchAccess(branchAccessResolver.resolve(actor), receipt.getBranchId());
        Page<ReceiptIncident> page = receiptIncidentRepository.findByTenantIdAndGoodsReceiptId(
                actor.tenantId(), receipt.getId(), safePageable(requestedPageable));
        return PageResponse.from(page, ReceiptIncidentResponse::from);
    }

    public ReceiptIncidentResponse create(UUID receiptId, CreateReceiptIncidentRequest request) {
        AuthenticatedUser actor = requireManagingActor();
        UUID tenantId = actor.tenantId();

        // Compartir el lock de la recepcion serializa create/resolve con confirm.
        GoodsReceipt receipt = requireReceiptForUpdate(tenantId, receiptId);
        requireBranchAccess(branchAccessResolver.resolve(actor), receipt.getBranchId());
        ValidatedRequest validated = validateRequest(tenantId, receipt.getId(), request);

        ReceiptIncident incident = ReceiptIncident.builder()
                .branchId(receipt.getBranchId())
                .goodsReceiptId(receipt.getId())
                .goodsReceiptItemId(validated.itemId())
                .incidentType(request.incidentType())
                .status(ReceiptIncidentStatus.open)
                .quantityAffected(validated.quantityAffected())
                .notes(validated.notes())
                .createdByUserId(actor.userId())
                .build();
        incident.setTenantId(tenantId);
        return ReceiptIncidentResponse.from(receiptIncidentRepository.saveAndFlush(incident));
    }

    public ReceiptIncidentResponse resolve(UUID incidentId) {
        AuthenticatedUser actor = requireManagingActor();
        UUID tenantId = actor.tenantId();
        ReceiptIncident current = requireIncident(tenantId, incidentId);

        // Mismo primer lock que confirm; despues se bloquea la incidencia que se va a mutar.
        GoodsReceipt receipt = requireReceiptForUpdate(tenantId, current.getGoodsReceiptId());
        requireBranchAccess(branchAccessResolver.resolve(actor), receipt.getBranchId());
        ReceiptIncident incident = receiptIncidentRepository
                .findForUpdateByTenantIdAndId(tenantId, incidentId)
                .orElseThrow(ReceiptIncidentService::incidentNotFound);
        if (!incident.getGoodsReceiptId().equals(receipt.getId())) {
            throw incidentNotFound();
        }
        if (incident.getStatus() == ReceiptIncidentStatus.resolved) {
            throw BusinessException.conflict(
                    "RECEIPT_INCIDENT_ALREADY_RESOLVED", "La incidencia ya fue resuelta.");
        }

        incident.setStatus(ReceiptIncidentStatus.resolved);
        incident.setResolvedByUserId(actor.userId());
        incident.setResolvedAt(Instant.now());
        return ReceiptIncidentResponse.from(receiptIncidentRepository.saveAndFlush(incident));
    }

    private ValidatedRequest validateRequest(
            UUID tenantId, UUID receiptId, CreateReceiptIncidentRequest request) {
        if (request == null || request.incidentType() == null) {
            throw badRequest("RECEIPT_INCIDENT_INVALID_REQUEST", "Los datos de la incidencia son requeridos.");
        }
        String notes = normalizeNotes(request.notes());
        UUID itemId = request.goodsReceiptItemId();
        BigDecimal quantity = request.quantityAffected();
        if (itemId == null) {
            if (quantity != null) {
                throw badRequest(
                        "RECEIPT_INCIDENT_QUANTITY_NOT_ALLOWED",
                        "La cantidad afectada solo aplica a incidencias asociadas a un producto.");
            }
            return new ValidatedRequest(null, null, notes);
        }

        GoodsReceiptItem item = goodsReceiptItemRepository
                .findByTenantIdAndId(tenantId, itemId)
                .orElseThrow(ReceiptIncidentService::receiptItemNotFound);
        if (!item.getGoodsReceiptId().equals(receiptId)) {
            throw badRequest(
                    "RECEIPT_INCIDENT_ITEM_MISMATCH",
                    "El producto recibido no pertenece a la recepcion indicada.");
        }
        if (quantity == null) {
            throw badRequest(
                    "RECEIPT_INCIDENT_QUANTITY_REQUIRED",
                    "La cantidad afectada es obligatoria para una incidencia por producto.");
        }
        if (!fitsDecimal(quantity, 12, 3) || quantity.signum() <= 0) {
            throw badRequest(
                    "RECEIPT_INCIDENT_INVALID_QUANTITY", "La cantidad afectada no es valida.");
        }
        if (quantity.compareTo(item.getReceivedQuantity()) > 0) {
            throw badRequest(
                    "RECEIPT_INCIDENT_QUANTITY_EXCEEDS_ITEM",
                    "La cantidad afectada no puede superar la cantidad recibida.");
        }
        Unit unit = unitRepository.findByTenantIdAndId(tenantId, item.getUnitId())
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "RECEIPT_INCIDENT_UNIT_NOT_FOUND",
                        "Unidad de la linea recibida no encontrada."));
        if (!unit.getAllowsDecimals() && !isInteger(quantity)) {
            throw badRequest(
                    "RECEIPT_INCIDENT_INVALID_QUANTITY",
                    "La unidad recibida solo admite cantidades enteras.");
        }
        return new ValidatedRequest(item.getId(), quantity, notes);
    }

    private AuthenticatedUser requireReadActor() {
        AuthenticatedUser actor = currentUser.require();
        if (actor.roleId() == null
                || READ_PERMISSIONS.stream().noneMatch(permission -> permissionResolver.hasPermission(
                        actor.tenantId(), actor.roleId(), permission))) {
            throw BusinessException.forbidden(
                    "ACCESS_DENIED", "No tienes permiso para consultar incidencias de recepcion.");
        }
        return actor;
    }

    private AuthenticatedUser requireManagingActor() {
        AuthenticatedUser actor = currentUser.require();
        if (actor.roleId() == null
                || !permissionResolver.hasPermission(
                        actor.tenantId(), actor.roleId(), MANAGE_PERMISSION)) {
            throw BusinessException.forbidden(
                    "ACCESS_DENIED", "No tienes permiso para realizar esta operación de recepción.");
        }
        tenantCapabilityGuard.ensureTenantCapability(actor.tenantId(), SaasCapability.receiving);
        return actor;
    }

    private GoodsReceipt requireReceipt(UUID tenantId, UUID id) {
        return goodsReceiptRepository
                .findByTenantIdAndId(tenantId, id)
                .orElseThrow(ReceiptIncidentService::receiptNotFound);
    }

    private GoodsReceipt requireReceiptForUpdate(UUID tenantId, UUID id) {
        return goodsReceiptRepository
                .findForUpdateByTenantIdAndId(tenantId, id)
                .orElseThrow(ReceiptIncidentService::receiptNotFound);
    }

    private ReceiptIncident requireIncident(UUID tenantId, UUID id) {
        return receiptIncidentRepository
                .findByTenantIdAndId(tenantId, id)
                .orElseThrow(ReceiptIncidentService::incidentNotFound);
    }

    private static void requireBranchAccess(BranchAccess access, UUID branchId) {
        if (!access.allows(branchId)) {
            throw BusinessException.forbidden(
                    "BRANCH_ACCESS_DENIED", "No tienes acceso a esta sucursal.");
        }
    }

    private static Pageable safePageable(Pageable requested) {
        int size = Math.min(Math.max(requested.getPageSize(), 1), 100);
        return PageRequest.of(
                Math.max(requested.getPageNumber(), 0),
                size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    private static String normalizeNotes(String notes) {
        if (notes == null) {
            throw badRequest(
                    "RECEIPT_INCIDENT_INVALID_NOTES",
                    "Las notas son obligatorias y no pueden superar 1000 caracteres.");
        }
        String normalized = notes.trim();
        if (normalized.isEmpty() || normalized.length() > 1000) {
            throw badRequest(
                    "RECEIPT_INCIDENT_INVALID_NOTES",
                    "Las notas son obligatorias y no pueden superar 1000 caracteres.");
        }
        return normalized;
    }

    private static boolean fitsDecimal(BigDecimal value, int precision, int scale) {
        BigDecimal normalized = value.stripTrailingZeros();
        int fractionDigits = Math.max(normalized.scale(), 0);
        int integerDigits = Math.max(normalized.precision() - normalized.scale(), 0);
        return fractionDigits <= scale && integerDigits <= precision - scale;
    }

    private static boolean isInteger(BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 0;
    }

    private static BusinessException receiptNotFound() {
        return new BusinessException(
                HttpStatus.NOT_FOUND,
                "GOODS_RECEIPT_NOT_FOUND",
                "Recepcion de compra no encontrada.");
    }

    private static BusinessException receiptItemNotFound() {
        return new BusinessException(
                HttpStatus.NOT_FOUND,
                "RECEIPT_INCIDENT_ITEM_NOT_FOUND",
                "Producto recibido no encontrado.");
    }

    private static BusinessException incidentNotFound() {
        return new BusinessException(
                HttpStatus.NOT_FOUND,
                "RECEIPT_INCIDENT_NOT_FOUND",
                "Incidencia de recepcion no encontrada.");
    }

    private static BusinessException badRequest(String code, String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, code, message);
    }

    private record ValidatedRequest(UUID itemId, BigDecimal quantityAffected, String notes) {}
}

package com.omniretail.backend.auth.service;

import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.BranchStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserStatus;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.auth.dto.ChangeActiveBranchRequest;
import com.omniretail.backend.auth.dto.CurrentSessionResponse;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.repository.SessionRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.validation.UnknownFields;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sucursal activa de la sesion actual (MockAuthRepository.setActiveBranchId + canUserOperateBranch).
 * Solo empleados, y solo una sucursal activa del tenant que este en sus sucursales asignadas: sin el
 * bypass de {@code branchScope == all}, igual que el selector del frontend. Todo rechazo responde el
 * mismo error, sin distinguir si la sucursal no existe o no esta permitida.
 */
@Service
@RequiredArgsConstructor
public class ActiveBranchService {

    static final String BRANCH_NOT_ALLOWED_MESSAGE = "La sucursal seleccionada no está autorizada para esta sesión.";

    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final BranchRepository branchRepository;
    private final CurrentSessionService currentSessionService;

    /** Solo cambia la sesion del token: las demas sesiones del usuario conservan su sucursal. */
    @Transactional
    public CurrentSessionResponse change(AuthenticatedUser actor, ChangeActiveBranchRequest request) {
        if (actor.userType() != UserType.employee) {
            throw branchNotAllowed();
        }
        UnknownFields.reject(request.unknownFields());

        Instant now = Instant.now();
        Session session = sessionRepository.findById(actor.sessionId())
                .filter(found -> found.getUserId().equals(actor.userId()))
                .filter(found -> found.getRevokedAt() == null && found.getExpiresAt().isAfter(now))
                .orElseThrow(ActiveBranchService::branchNotAllowed);
        User user = userRepository.findByTenantIdAndId(actor.tenantId(), actor.userId())
                .filter(found -> found.getType() == UserType.employee && found.getStatus() == UserStatus.active)
                .orElseThrow(ActiveBranchService::branchNotAllowed);
        if (request.branchId() == null) {
            throw branchNotAllowed();
        }
        Branch branch = branchRepository.findByTenantIdAndId(actor.tenantId(), request.branchId())
                .filter(found -> found.getStatus() == BranchStatus.active)
                .filter(found -> BranchAccessResolver.allowedBranchIds(user).contains(found.getId()))
                .orElseThrow(ActiveBranchService::branchNotAllowed);

        session.setActiveBranchId(branch.getId());
        sessionRepository.saveAndFlush(session);
        // Misma respuesta que GET /auth/me: el frontend refresca la sesion con una sola llamada.
        return currentSessionService.resolve(actor);
    }

    private static BusinessException branchNotAllowed() {
        return new BusinessException(HttpStatus.FORBIDDEN, "BRANCH_NOT_ALLOWED", BRANCH_NOT_ALLOWED_MESSAGE);
    }
}

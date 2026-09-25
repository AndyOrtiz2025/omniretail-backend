package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.dto.CreateUserRequest;
import com.omniretail.backend.administration.dto.UpdateUserRequest;
import com.omniretail.backend.administration.dto.UserResponse;
import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.BranchStatus;
import com.omniretail.backend.administration.entity.Role;
import com.omniretail.backend.administration.entity.RoleStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserStatus;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.repository.RoleRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.PermissionResolver;
import com.omniretail.backend.shared.security.SessionRevoker;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final BranchRepository branchRepository;
    private final CurrentUser currentUser;
    private final PermissionResolver permissionResolver;
    private final SessionRevoker sessionRevoker;

    /** Este endpoint administra EMPLEADOS unicamente (ver GetEmployeesService.ts); nunca clientes. */
    public PageResponse<UserResponse> listUsers(UserStatus status, Pageable pageable) {
        UUID tenantId = currentUser.require().tenantId();
        Page<User> page = status != null
                ? userRepository.findByTenantIdAndTypeAndStatus(tenantId, UserType.employee, status, pageable)
                : userRepository.findByTenantIdAndType(tenantId, UserType.employee, pageable);
        return PageResponse.from(page, UserResponse::from);
    }

    public UserResponse getUserById(UUID id) {
        User user = getEmployee(currentUser.require().tenantId(), id);
        return UserResponse.from(user);
    }

    public UserResponse createUser(CreateUserRequest request) {
        UUID tenantId = currentUser.require().tenantId();

        String email = request.email().trim().toLowerCase();
        // Unico entre TODAS las tiendas para empleados (CreateEmployeeService.ts): un mismo correo
        // no puede pertenecer a dos empleados, sin importar el tenant.
        if (userRepository.existsByEmailIgnoreCaseAndType(email, UserType.employee)) {
            throw BusinessException.conflict("USER_EMAIL_EXISTS", "Ya existe un usuario con el correo " + email);
        }

        String employeeCode = request.employeeCode().trim();
        if (userRepository.existsByTenantIdAndEmployeeCodeIgnoreCase(tenantId, employeeCode)) {
            throw BusinessException.conflict(
                    "EMPLOYEE_CODE_EXISTS", "Ya existe un empleado con el código " + employeeCode);
        }

        ensureDelegatableRole(tenantId, request.roleId());
        if (request.branchId() != null) {
            ensureActiveBranch(tenantId, request.branchId());
        }
        ensureActiveBranches(tenantId, request.allowedBranchIds(), Set.of());

        User user = User.builder()
                .name(request.name().trim())
                .email(email)
                .phone(normalizePhone(request.phone()))
                .employeeCode(employeeCode)
                .type(UserType.employee)
                .status(request.status() != null ? request.status() : UserStatus.active)
                .roleId(request.roleId())
                .branchId(request.branchId())
                .allowedBranchIds(request.allowedBranchIds())
                .build();
        user.setTenantId(tenantId);
        User saved = userRepository.save(user);
        return UserResponse.from(saved);
    }

    public UserResponse updateUser(UUID id, UpdateUserRequest request) {
        UUID tenantId = currentUser.require().tenantId();
        User user = getEmployee(tenantId, id);

        ensureStatusEditable(request.status());

        String employeeCode = request.employeeCode().trim();
        if (!employeeCode.equalsIgnoreCase(user.getEmployeeCode())
                && userRepository.existsByTenantIdAndEmployeeCodeIgnoreCaseAndIdNot(tenantId, employeeCode, id)) {
            throw BusinessException.conflict(
                    "EMPLOYEE_CODE_EXISTS", "Ya existe un empleado con el código " + employeeCode);
        }

        ensureDelegatableRole(tenantId, request.roleId());
        if (request.branchId() != null) {
            ensureActiveBranch(tenantId, request.branchId());
        }
        Set<UUID> previousBranchIds =
                user.getAllowedBranchIds() != null ? new HashSet<>(user.getAllowedBranchIds()) : Set.of();
        ensureActiveBranches(tenantId, request.allowedBranchIds(), previousBranchIds);

        // Cambios de seguridad relevantes (§14, UpdateEmployeeService.ts) -- exigen que el empleado
        // vuelva a autenticarse con su contexto/permisos actuales. Un update que solo toca
        // name/phone/employeeCode NO revoca nada.
        boolean statusChanged = user.getStatus() != request.status();
        boolean roleChanged = !Objects.equals(user.getRoleId(), request.roleId());
        boolean branchesChanged = !sameBranchSet(previousBranchIds, request.allowedBranchIds());

        user.setName(request.name().trim());
        user.setPhone(normalizePhone(request.phone()));
        user.setEmployeeCode(employeeCode);
        user.setRoleId(request.roleId());
        user.setBranchId(request.branchId());
        user.setAllowedBranchIds(request.allowedBranchIds());
        user.setStatus(request.status());

        User saved = userRepository.save(user);

        if (statusChanged || roleChanged || branchesChanged) {
            sessionRevoker.revokeAllSessions(id);
        }

        return UserResponse.from(saved);
    }

    public UserResponse updateUserStatus(UUID id, UserStatus newStatus) {
        UUID tenantId = currentUser.require().tenantId();
        User user = getEmployee(tenantId, id);

        ensureStatusEditable(newStatus);

        boolean statusChanged = user.getStatus() != newStatus;
        user.setStatus(newStatus);
        User saved = userRepository.save(user);

        if (statusChanged) {
            sessionRevoker.revokeAllSessions(id);
        }

        return UserResponse.from(saved);
    }

    /** Un customer se trata como inexistente para este endpoint (admin-users solo gestiona employees). */
    private User getEmployee(UUID tenantId, UUID id) {
        User user = userRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Usuario no encontrado."));
        if (user.getType() != UserType.employee) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Usuario no encontrado.");
        }
        return user;
    }

    /** {@code archived} no forma parte del ciclo de vida editable de un empleado (EDITABLE_STATUSES). */
    private void ensureStatusEditable(UserStatus status) {
        if (status == UserStatus.archived) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "STATUS_ARCHIVED_NOT_ALLOWED",
                    "Un empleado no se archiva editando su estado.");
        }
    }

    private static boolean sameBranchSet(Set<UUID> previous, List<UUID> updated) {
        Set<UUID> updatedSet = updated != null ? new HashSet<>(updated) : Set.of();
        return previous.equals(updatedSet);
    }

    private static String normalizePhone(String phone) {
        return phone != null && !phone.isBlank() ? phone.trim() : null;
    }

    /**
     * Control anti-escalamiento de privilegios (mismo algoritmo que {@code
     * RoleService.ensureDelegatablePermissions}): el actor solo puede asignar un rol cuyos permisos
     * ya posee todos, sin excepción de "super admin"/{@code isSystem}.
     */
    private void ensureDelegatableRole(UUID tenantId, UUID roleId) {
        Role role = roleRepository.findByTenantIdAndId(tenantId, roleId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND", "El rol no existe."));
        if (role.getStatus() != RoleStatus.active) {
            throw BusinessException.conflict("ROLE_INACTIVE", "El rol asignado no está activo.");
        }

        List<String> rolePermissions = role.getPermissions();
        if (rolePermissions == null || rolePermissions.isEmpty()) {
            return;
        }
        AuthenticatedUser actor = currentUser.require();
        UUID actorRoleId = actor.roleId();
        boolean canDelegateAll = actorRoleId != null
                && rolePermissions.stream()
                        .allMatch(permission -> permissionResolver.hasPermission(tenantId, actorRoleId, permission));
        if (!canDelegateAll) {
            throw BusinessException.forbidden(
                    "PERMISSION_NOT_DELEGABLE", "No puedes asignar un rol con permisos que tu propia cuenta no tiene.");
        }
    }

    private void ensureActiveBranch(UUID tenantId, UUID branchId) {
        Branch branch = branchRepository.findByTenantIdAndId(tenantId, branchId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "BRANCH_NOT_FOUND", "La sucursal asignada no existe."));
        if (branch.getStatus() != BranchStatus.active) {
            throw BusinessException.conflict("BRANCH_INACTIVE", "La sucursal asignada no está activa.");
        }
    }

    /**
     * Sucursales ya asignadas al empleado se conservan aunque hoy estén inactivas
     * (ensureEmployeeBranchIds en serviceHelpers.ts); las nuevas deben existir en el tenant y estar
     * activas.
     */
    private void ensureActiveBranches(UUID tenantId, List<UUID> branchIds, Set<UUID> alreadyAssigned) {
        if (branchIds == null || branchIds.isEmpty()) {
            return;
        }
        for (UUID branchId : branchIds) {
            Branch branch = branchRepository.findByTenantIdAndId(tenantId, branchId)
                    .orElseThrow(() -> new BusinessException(
                            HttpStatus.NOT_FOUND, "BRANCH_NOT_FOUND", "La sucursal asignada no existe."));
            if (!alreadyAssigned.contains(branchId) && branch.getStatus() != BranchStatus.active) {
                throw BusinessException.conflict("BRANCH_INACTIVE", "La sucursal asignada no está activa.");
            }
        }
    }
}

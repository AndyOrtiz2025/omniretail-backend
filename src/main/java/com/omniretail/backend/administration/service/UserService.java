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
import java.util.List;
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

    public PageResponse<UserResponse> listUsers(UserType type, UserStatus status, Pageable pageable) {
        UUID tenantId = currentUser.require().tenantId();
        Page<User> page;
        if (type != null && status != null) {
            page = userRepository.findByTenantIdAndTypeAndStatus(tenantId, type, status, pageable);
        } else if (type != null) {
            page = userRepository.findByTenantIdAndType(tenantId, type, pageable);
        } else if (status != null) {
            page = userRepository.findByTenantIdAndStatus(tenantId, status, pageable);
        } else {
            page = userRepository.findByTenantId(tenantId, pageable);
        }
        return PageResponse.from(page, UserResponse::from);
    }

    public UserResponse getUserById(UUID id) {
        UUID tenantId = currentUser.require().tenantId();
        User user = userRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Usuario no encontrado."));
        return UserResponse.from(user);
    }

    public UserResponse createUser(CreateUserRequest request) {
        UUID tenantId = currentUser.require().tenantId();

        String email = request.email().trim().toLowerCase();
        if (userRepository.existsByTenantIdAndEmailIgnoreCase(tenantId, email)) {
            throw BusinessException.conflict("USER_EMAIL_EXISTS", "Ya existe un usuario con el correo " + email);
        }

        String employeeCode = null;
        if (request.employeeCode() != null && !request.employeeCode().isBlank()) {
            employeeCode = request.employeeCode().trim();
            if (userRepository.existsByTenantIdAndEmployeeCodeIgnoreCase(tenantId, employeeCode)) {
                throw BusinessException.conflict(
                        "EMPLOYEE_CODE_EXISTS", "Ya existe un empleado con el código " + employeeCode);
            }
        }

        if (request.roleId() != null) {
            ensureDelegatableRole(tenantId, request.roleId());
        }
        if (request.branchId() != null) {
            ensureActiveBranch(tenantId, request.branchId());
        }
        ensureActiveBranches(tenantId, request.allowedBranchIds());

        User user = User.builder()
                .name(request.name().trim())
                .email(email)
                .phone(request.phone() != null ? request.phone().trim() : null)
                .employeeCode(employeeCode)
                .type(request.type())
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
        User user = userRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Usuario no encontrado."));

        String employeeCode = null;
        if (request.employeeCode() != null && !request.employeeCode().isBlank()) {
            employeeCode = request.employeeCode().trim();
            if (!employeeCode.equalsIgnoreCase(user.getEmployeeCode())
                    && userRepository.existsByTenantIdAndEmployeeCodeIgnoreCaseAndIdNot(tenantId, employeeCode, id)) {
                throw BusinessException.conflict(
                        "EMPLOYEE_CODE_EXISTS", "Ya existe un empleado con el código " + employeeCode);
            }
        }

        if (request.roleId() != null) {
            ensureDelegatableRole(tenantId, request.roleId());
        }
        if (request.branchId() != null) {
            ensureActiveBranch(tenantId, request.branchId());
        }
        ensureActiveBranches(tenantId, request.allowedBranchIds());

        user.setName(request.name().trim());
        user.setPhone(request.phone() != null ? request.phone().trim() : null);
        user.setEmployeeCode(employeeCode);
        user.setRoleId(request.roleId());
        user.setBranchId(request.branchId());
        user.setAllowedBranchIds(request.allowedBranchIds());
        user.setStatus(request.status());

        User saved = userRepository.save(user);
        return UserResponse.from(saved);
    }

    public UserResponse updateUserStatus(UUID id, UserStatus newStatus) {
        UUID tenantId = currentUser.require().tenantId();
        User user = userRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Usuario no encontrado."));
        user.setStatus(newStatus);
        User saved = userRepository.save(user);
        return UserResponse.from(saved);
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

    private void ensureActiveBranches(UUID tenantId, List<UUID> branchIds) {
        if (branchIds == null || branchIds.isEmpty()) {
            return;
        }
        for (UUID branchId : branchIds) {
            ensureActiveBranch(tenantId, branchId);
        }
    }
}

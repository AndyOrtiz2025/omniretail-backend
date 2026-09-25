package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.dto.CreateRoleRequest;
import com.omniretail.backend.administration.dto.RoleResponse;
import com.omniretail.backend.administration.dto.UpdateRoleRequest;
import com.omniretail.backend.administration.entity.BranchScope;
import com.omniretail.backend.administration.entity.Role;
import com.omniretail.backend.administration.entity.RoleStatus;
import com.omniretail.backend.administration.repository.RoleRepository;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.CurrentUser;
import java.util.ArrayList;
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
public class RoleService {

    private final RoleRepository roleRepository;
    private final CurrentUser currentUser;

    public PageResponse<RoleResponse> listRoles(RoleStatus status, Pageable pageable) {
        UUID tenantId = currentUser.require().tenantId();
        Page<Role> page = status != null
                ? roleRepository.findByTenantIdAndStatus(tenantId, status, pageable)
                : roleRepository.findByTenantId(tenantId, pageable);
        return PageResponse.from(page, RoleResponse::from);
    }

    public List<RoleResponse> listActiveRoles() {
        UUID tenantId = currentUser.require().tenantId();
        return roleRepository.findByTenantIdAndStatus(tenantId, RoleStatus.active).stream()
                .map(RoleResponse::from)
                .toList();
    }

    public RoleResponse getRoleById(UUID id) {
        UUID tenantId = currentUser.require().tenantId();
        Role role = roleRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND", "Rol no encontrado."));
        return RoleResponse.from(role);
    }

    public RoleResponse createRole(CreateRoleRequest request) {
        UUID tenantId = currentUser.require().tenantId();
        String name = request.name().trim();
        if (roleRepository.existsByTenantIdAndNameIgnoreCase(tenantId, name)) {
            throw new BusinessException(
                    HttpStatus.CONFLICT, "ROLE_NAME_EXISTS", "Ya existe un rol con el nombre " + name);
        }
        Role role = Role.builder()
                .name(name)
                .description(request.description() != null ? request.description().trim() : null)
                .isSystem(false)
                .permissions(request.permissions() != null ? new ArrayList<>(request.permissions()) : new ArrayList<>())
                .branchScope(request.branchScope() != null ? request.branchScope() : BranchScope.assigned)
                .status(request.status() != null ? request.status() : RoleStatus.active)
                .build();
        role.setTenantId(tenantId);
        Role saved = roleRepository.save(role);
        return RoleResponse.from(saved);
    }

    public RoleResponse updateRole(UUID id, UpdateRoleRequest request) {
        UUID tenantId = currentUser.require().tenantId();
        Role role = roleRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND", "Rol no encontrado."));

        if (Boolean.TRUE.equals(role.getIsSystem())) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "CANNOT_MODIFY_SYSTEM_ROLE", "Los roles del sistema no pueden ser modificados.");
        }

        String newName = request.name().trim();
        if (!newName.equals(role.getName())
                && roleRepository.existsByTenantIdAndNameIgnoreCaseAndIdNot(tenantId, newName, id)) {
            throw new BusinessException(
                    HttpStatus.CONFLICT, "ROLE_NAME_EXISTS", "Ya existe un rol con el nombre " + newName);
        }

        role.setName(newName);
        role.setDescription(request.description() != null ? request.description().trim() : null);
        role.setPermissions(request.permissions() != null ? new ArrayList<>(request.permissions()) : new ArrayList<>());
        role.setBranchScope(request.branchScope() != null ? request.branchScope() : BranchScope.assigned);
        if (request.status() != null) {
            role.setStatus(request.status());
        }

        Role saved = roleRepository.save(role);
        return RoleResponse.from(saved);
    }

    public void archiveRole(UUID id) {
        UUID tenantId = currentUser.require().tenantId();
        Role role = roleRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND", "Rol no encontrado."));

        if (Boolean.TRUE.equals(role.getIsSystem())) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "CANNOT_ARCHIVE_SYSTEM_ROLE", "Los roles del sistema no pueden ser archivados.");
        }

        role.setStatus(RoleStatus.archived);
        roleRepository.save(role);
    }
}

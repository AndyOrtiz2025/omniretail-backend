package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.CreateRoleRequest;
import com.omniretail.backend.administration.dto.RoleResponse;
import com.omniretail.backend.administration.dto.UpdateRoleRequest;
import com.omniretail.backend.administration.entity.RoleStatus;
import com.omniretail.backend.administration.service.RoleService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/administration/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @RequirePermission("admin.roles.read")
    @GetMapping
    public PageResponse<RoleResponse> list(
            @RequestParam(required = false) RoleStatus status, @PageableDefault(size = 20) Pageable pageable) {
        return roleService.listRoles(status, pageable);
    }

    @RequirePermission("admin.roles.read")
    @GetMapping("/active")
    public List<RoleResponse> listActive() {
        return roleService.listActiveRoles();
    }

    @RequirePermission("admin.roles.read")
    @GetMapping("/{id}")
    public RoleResponse getById(@PathVariable UUID id) {
        return roleService.getRoleById(id);
    }

    @RequirePermission("admin.roles.manage")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoleResponse create(@Valid @RequestBody CreateRoleRequest request) {
        return roleService.createRole(request);
    }

    @RequirePermission("admin.roles.manage")
    @PutMapping("/{id}")
    public RoleResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateRoleRequest request) {
        return roleService.updateRole(id, request);
    }

    @RequirePermission("admin.roles.manage")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        roleService.archiveRole(id);
    }
}

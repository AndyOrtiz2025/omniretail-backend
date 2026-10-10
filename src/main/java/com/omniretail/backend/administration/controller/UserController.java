package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.CreateUserRequest;
import com.omniretail.backend.administration.dto.EmployeeAuthSummariesRequest;
import com.omniretail.backend.administration.dto.UpdateUserRequest;
import com.omniretail.backend.administration.dto.UserResponse;
import com.omniretail.backend.administration.entity.UserStatus;
import com.omniretail.backend.administration.service.UserService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.EmployeeAuthSummary;
import com.omniretail.backend.shared.security.EmployeeInvitationPort;
import com.omniretail.backend.shared.security.EmployeeInviteResult;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/administration/users")
@RequiredArgsConstructor
@Tag(name = "Usuarios y empleados", description = "Gestión de empleados del negocio, asignación de roles y envío de invitaciones.")
public class UserController {

    private final UserService userService;
    private final EmployeeInvitationPort employeeInvitationPort;
    private final CurrentUser currentUser;

    @RequirePermission("admin.users.read")
    @GetMapping
    @Operation(
            summary = "Listar empleados paginados",
            description = "Devuelve el listado de empleados del tenant con paginación y filtro opcional por estado (`active`, `blocked`).")
    public PageResponse<UserResponse> list(
            @RequestParam(required = false) UserStatus status, @PageableDefault(size = 20) Pageable pageable) {
        return userService.listUsers(status, pageable);
    }

    @RequirePermission("admin.users.read")
    @GetMapping("/{id}")
    @Operation(
            summary = "Consultar empleado por ID",
            description = "Obtiene los detalles del usuario, su rol asignado, sucursal base y sucursales permitidas.")
    public UserResponse getById(@PathVariable UUID id) {
        return userService.getUserById(id);
    }

    @RequirePermission("admin.users.manage")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Crear nuevo empleado",
            description = "Registra un empleado en el negocio asignándole rol y sucursal. Valida límites del plan SaaS.")
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return userService.createUser(request);
    }

    @RequirePermission("admin.users.manage")
    @PutMapping("/{id}")
    @Operation(
            summary = "Actualizar datos del empleado",
            description = "Modifica los datos personales, rol o sucursales asignadas a un empleado.")
    public UserResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return userService.updateUser(id, request);
    }

    @RequirePermission("admin.users.manage")
    @PutMapping("/{id}/status")
    @Operation(
            summary = "Cambiar estado de empleado",
            description = "Activa o bloquea a un empleado. Al bloquearlo, se invalidan inmediatamente sus sesiones activas.")
    public UserResponse updateStatus(@PathVariable UUID id, @RequestParam UserStatus status) {
        return userService.updateUserStatus(id, status);
    }

    @RequirePermission("admin.users.manage")
    @PostMapping("/{id}/invite")
    @Operation(
            summary = "Enviar invitación de acceso",
            description = "Genera un enlace de activación por correo electrónico para que el empleado establezca su contraseña.")
    public EmployeeInviteResult invite(@PathVariable UUID id) {
        return employeeInvitationPort.inviteEmployee(currentUser.require().tenantId(), id);
    }

    @RequirePermission("admin.users.manage")
    @PostMapping("/{id}/resend-invite")
    @Operation(
            summary = "Reenviar invitación de acceso",
            description = "Reenvía el correo de activación con un token renovado.")
    public EmployeeInviteResult resendInvite(@PathVariable UUID id) {
        return employeeInvitationPort.inviteEmployee(currentUser.require().tenantId(), id);
    }

    @RequirePermission("admin.users.read")
    @PostMapping("/auth-summaries")
    @Operation(
            summary = "Consultar resúmenes de autenticación",
            description = "Obtiene el estado de cuenta y MFA de una lista de IDs de empleados.")
    public List<EmployeeAuthSummary> authSummaries(
            @Valid @RequestBody EmployeeAuthSummariesRequest request) {
        return employeeInvitationPort.getAuthSummaries(currentUser.require().tenantId(), request.userIds());
    }
}

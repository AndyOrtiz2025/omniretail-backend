package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.CreateUserRequest;
import com.omniretail.backend.administration.dto.UpdateUserRequest;
import com.omniretail.backend.administration.dto.UserResponse;
import com.omniretail.backend.administration.entity.UserStatus;
import com.omniretail.backend.administration.service.UserService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
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
public class UserController {

    private final UserService userService;

    @RequirePermission("admin.users.read")
    @GetMapping
    public PageResponse<UserResponse> list(
            @RequestParam(required = false) UserStatus status, @PageableDefault(size = 20) Pageable pageable) {
        return userService.listUsers(status, pageable);
    }

    @RequirePermission("admin.users.read")
    @GetMapping("/{id}")
    public UserResponse getById(@PathVariable UUID id) {
        return userService.getUserById(id);
    }

    @RequirePermission("admin.users.manage")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return userService.createUser(request);
    }

    @RequirePermission("admin.users.manage")
    @PutMapping("/{id}")
    public UserResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return userService.updateUser(id, request);
    }

    @RequirePermission("admin.users.manage")
    @PutMapping("/{id}/status")
    public UserResponse updateStatus(@PathVariable UUID id, @RequestParam UserStatus status) {
        return userService.updateUserStatus(id, status);
    }
}

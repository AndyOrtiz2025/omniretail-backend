package com.omniretail.backend.auth.controller;

import com.omniretail.backend.auth.dto.CurrentSessionResponse;
import com.omniretail.backend.auth.dto.LoginRequest;
import com.omniretail.backend.auth.dto.LoginResponse;
import com.omniretail.backend.auth.service.AuthService;
import com.omniretail.backend.auth.service.CurrentSessionService;
import com.omniretail.backend.shared.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /login} es publico; {@code /logout} y {@code /me} exigen token (ver SecurityConfig). */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final CurrentSessionService currentSessionService;
    private final CurrentUser currentUser;

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout() {
        authService.logout(currentUser.require().sessionId());
    }

    @GetMapping("/me")
    public CurrentSessionResponse me() {
        return currentSessionService.resolve(currentUser.require());
    }
}

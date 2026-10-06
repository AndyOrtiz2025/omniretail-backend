package com.omniretail.backend.auth.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Sin GOOGLE_CLIENT_ID la app arranca igual y el login con Google responde 503 claro. */
@SpringBootTest(properties = "app.google.client-id=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class GoogleLoginNotConfiguredTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void withoutClientIdRespondsServiceUnavailable() throws Exception {
        mockMvc.perform(post("/api/v1/auth/google").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\": \"cualquier-token\", \"tenantSlug\": \"tienda\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GOOGLE_LOGIN_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.message").value("Inicio de sesión con Google no configurado."));
    }
}

package com.omniretail.backend.shared.security;

import com.omniretail.backend.shared.config.CorsProperties;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Capa 1 (Authentication) del modelo de seguridad. Las capas 2-4 (entitlement, permiso de rol y
 * sucursal) se validan despues, en los services de cada modulo.
 *
 * <p>El JWT viaja por ahora en el header {@code Authorization: Bearer}. Si se decide llevarlo en
 * cookie HttpOnly, aqui se agrega un {@code BearerTokenResolver} propio y se reactiva CSRF.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** Rutas sin autenticacion. El resto de la API exige un JWT valido. */
    private static final String[] PUBLIC_PATHS = {
        "/actuator/health",
        "/actuator/info",
        "/api-docs/**",
        "/swagger-ui/**",
        "/swagger-ui.html",
        "/api/v1/auth/login",
        "/api/v1/public/**",
    };

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, TenantJwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        return http
                .csrf(csrf -> csrf.disable()) // API stateless con Bearer token: sin cookies de sesion que proteger.
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}

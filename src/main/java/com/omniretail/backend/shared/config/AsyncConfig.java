package com.omniretail.backend.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Habilita {@code @Async}; usa el executor de Spring Boot (hilos virtuales, ver spring.threads.virtual). */
@Configuration
@EnableAsync
public class AsyncConfig {
}

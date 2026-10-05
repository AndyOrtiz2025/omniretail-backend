package com.omniretail.backend.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Habilita {@code @Scheduled} (hoy: reintento de correos operativos). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}

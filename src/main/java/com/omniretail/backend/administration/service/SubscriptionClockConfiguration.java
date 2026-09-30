package com.omniretail.backend.administration.service;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SubscriptionClockConfiguration {

    @Bean
    public Clock subscriptionClock() {
        return Clock.systemUTC();
    }
}

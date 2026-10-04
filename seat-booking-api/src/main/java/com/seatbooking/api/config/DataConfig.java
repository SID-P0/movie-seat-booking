package com.seatbooking.api.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EntityScan(basePackages = "com.seatbooking.common.entity")
@EnableJpaRepositories(basePackages = "com.seatbooking.api.repository")
@EnableScheduling
public class DataConfig {
}

package com.seatbooking.consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.seatbooking.consumer")
@EntityScan(basePackages = "com.seatbooking.common.entity")
@EnableJpaRepositories(basePackages = "com.seatbooking.consumer.repository")
public class SeatBookingConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeatBookingConsumerApplication.class, args);
    }
}

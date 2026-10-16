package com.maheshclothes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
@EnableJpaRepositories(considerNestedRepositories = true)   // SalesRepositories / PurchaseRepositories hold nested interfaces
@EnableAsync
@EnableScheduling
public class MaheshClothesApplication {

    public static void main(String[] args) {
        SpringApplication.run(MaheshClothesApplication.class, args);
    }
}

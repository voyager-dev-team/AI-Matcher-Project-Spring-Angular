package com.voyager.sesmatcher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SesAiMatcherApplication {

    public static void main(String[] args) {
        SpringApplication.run(SesAiMatcherApplication.class, args);
    }
}

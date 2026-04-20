package com.finvanta;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class FinvantaApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinvantaApplication.class, args);
    }
}

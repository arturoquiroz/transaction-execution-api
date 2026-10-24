package com.aquiroz.transactions;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TransactionExecutionApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransactionExecutionApiApplication.class, args);
    }
}

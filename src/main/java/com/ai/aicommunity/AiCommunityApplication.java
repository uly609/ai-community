package com.ai.aicommunity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AiCommunityApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiCommunityApplication.class, args);
    }

}

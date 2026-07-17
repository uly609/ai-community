package com.ai.aicommunity.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.canal.article-cache")
public class CanalArticleCacheProperties {

    private boolean enabled = false;
    private String host = "localhost";
    private int port = 11111;
    private String destination = "example";
    private String username = "";
    private String password = "";
    private String subscribeRegex = "ai_community\\.article";
    private int batchSize = 100;
    private long idleSleepMillis = 1000L;
}

package com.novelagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.embedding")
public class EmbeddingConfig {

    private String apiKey;
    private String baseUrl;
    private String model;
}

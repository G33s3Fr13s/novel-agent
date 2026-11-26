package com.novelagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.llm")
public class LlmConfig {

    private String apiKey;
    private String baseUrl;
    private String model;
    private int maxTokens = 4096;
    private double temperature = 0.7;
}

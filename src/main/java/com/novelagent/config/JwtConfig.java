package com.novelagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.jwt")
public class JwtConfig {

    private String secret;
    private long accessTokenExpiration;     // ms
    private long refreshTokenExpiration;    // ms
    private String refreshTokenCookieName = "refresh_token";
}

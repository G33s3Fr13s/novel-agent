package com.novelagent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthResponse {

    private String accessToken;
    private String tokenType;
    private long expiresIn;
    private UserInfo user;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UserInfo {
        private UUID id;
        private String username;
        private String email;
        private String role;
    }

    public static AuthResponse of(String accessToken, long expiresIn, UUID userId, String username, String email, String role) {
        return AuthResponse.builder()
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresIn(expiresIn)
                .user(UserInfo.builder()
                        .id(userId)
                        .username(username)
                        .email(email)
                        .role(role)
                        .build())
                .build();
    }
}

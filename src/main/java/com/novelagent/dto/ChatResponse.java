package com.novelagent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatResponse {

    private String conversationId;
    private MessageInfo message;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MessageInfo {
        private String role;
        private String content;
        private Instant createdAt;
    }

    public static ChatResponse of(UUID conversationId, String content, Instant createdAt) {
        return ChatResponse.builder()
                .conversationId(conversationId.toString())
                .message(MessageInfo.builder()
                        .role("assistant")
                        .content(content)
                        .createdAt(createdAt)
                        .build())
                .build();
    }
}

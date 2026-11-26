package com.novelagent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class ChatRequest {

    private String conversationId;   // null = 新会话（已登录）
    private String novelId;          // 选了资料库则走双路检索，null = 普通聊天
    private String chapterId;        // 可选：限定只检索某一章

    private List<String> searchSites;    // 勾选的站点域名列表，null/空 = 不搜索

    @NotBlank(message = "消息不能为空")
    private String message;

    /**
     * 匿名用户传的历史消息，前端从 localStorage 读取后放到这里。
     * [{"role":"user","content":"你好"}, {"role":"assistant","content":"..."}, ...]
     * 已登录用户可以不传，后端会根据 conversationId 从 DB 查。
     */
    private List<MessageItem> history;

    @Data
    public static class MessageItem {
        private String role;
        private String content;
    }
}

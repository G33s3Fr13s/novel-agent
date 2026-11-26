package com.novelagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.config.LlmConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@Service
public class LlmService {

    private final LlmConfig llmConfig;
    private final DynamicConfigService configService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public LlmService(LlmConfig llmConfig, DynamicConfigService configService) {
        this.llmConfig = llmConfig;
        this.configService = configService;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Non-streaming chat — LLM 全部生成完才返回。适合短回复。
     */
    public String chat(List<Map<String, String>> messages) {
        return chat(messages, llmConfig.getMaxTokens());
    }

    /** 允许自定义 max_tokens，图谱构建等需要长输出的场景用 */
    public String chat(List<Map<String, String>> messages, int maxTokens) {
        String url = buildUrl();
        HttpEntity<Map<String, Object>> entity = buildRequest(messages, false, maxTokens);

        ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.POST, entity, Map.class);

        Map<String, Object> respBody = response.getBody();
        if (respBody == null) throw new RuntimeException("LLM returned empty response");

        List<Map<String, Object>> choices = (List<Map<String, Object>>) respBody.get("choices");
        if (choices == null || choices.isEmpty()) throw new RuntimeException("LLM returned no choices");

        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        return (String) message.get("content");
    }

    // ═══════════════════════════════════════════════════════════════
    // 流式聊天 — 打字机效果
    // ═══════════════════════════════════════════════════════════════

    /**
     * 关键点就两步：
     *
     * 1. 请求里加 "stream": true → LLM 每算出一个 token 就立刻返回，不等整句话写完
     * 2. 逐行读 HTTP 响应 → 每行是 "data: {json}" → 解析 delta.content → 回调 onToken
     *
     * LLM 返回的原始格式（SSE）：
     *   data: {"choices":[{"delta":{"content":"在"}}]}
     *   data: {"choices":[{"delta":{"content":"苍"}}]}
     *   data: [DONE]                          ← 生成结束的信号
     */
    public String chatStream(List<Map<String, String>> messages, Consumer<String> onToken) {
        return chatStream(messages, onToken, llmConfig.getMaxTokens());
    }

    /** 带自定义 maxTokens 的流式聊天，续写等长输出场景用 */
    public String chatStream(List<Map<String, String>> messages, Consumer<String> onToken, int maxTokens) {
        String url = buildUrl();
        HttpEntity<Map<String, Object>> entity = buildRequest(messages, true, maxTokens);  // ← stream=true

        // 关键：必须关闭 HTTP 响应缓冲，否则数据会憋在缓冲区里等攒够了才给
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setBufferRequestBody(false);
        RestTemplate streamTemplate = new RestTemplate(factory);

        StringBuilder fullContent = new StringBuilder();   // 收集完整回复，最后存 DB 用

        try {
            streamTemplate.execute(url, HttpMethod.POST,
                // ── 请求体写入 ──
                req -> {
                    req.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    req.getHeaders().setAll(entity.getHeaders().toSingleValueMap());
                    req.getBody().write(objectMapper.writeValueAsBytes(entity.getBody()));
                },
                // ── 响应体读取：逐行消费，来一行处理一行 ──
                res -> {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(res.getBody(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {

                            if (line.isEmpty()) continue;          // SSE 协议的空行，跳过

                            if (line.startsWith("data: ")) {
                                String data = line.substring(6);   // 切掉 "data: " 前缀
                                if ("[DONE]".equals(data)) break;  // LLM 说"我说完了"

                                // 解析 data 里的 JSON，提取 token
                                try {
                                    JsonNode root = objectMapper.readTree(data);
                                    JsonNode choices = root.get("choices");
                                    if (choices != null && choices.size() > 0) {
                                        JsonNode delta = choices.get(0).get("delta");
                                        if (delta != null) {
                                            JsonNode contentNode = delta.get("content");
                                            if (contentNode != null && !contentNode.isNull()) {
                                                String token = contentNode.asText();   // ← 这就是一个字
                                                fullContent.append(token);             // 攒起来（存 DB 用）
                                                onToken.accept(token);                 // 立即回调 → Controller → SseEmitter → 前端
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    log.debug("Failed to parse SSE data: {}", data);
                                }
                            }
                        }
                    }
                    return null;
                });
        } catch (Exception e) {
            // DeepSeek 长文本流式输出偶尔会 Premature EOF，这是外部 API 行为，
            // 已收到的内容仍然有效，不应丢弃。记录警告并返回部分内容。
            if (fullContent.length() > 0 && isPrematureEof(e)) {
                log.warn("LLM stream truncated (Premature EOF), returning {} chars collected so far", fullContent.length());
                return fullContent.toString();
            }
            log.error("Streaming request failed", e);
            throw new RuntimeException("LLM streaming failed: " + e.getMessage(), e);
        }

        log.debug("LLM streaming complete: {} chars", fullContent.length());
        return fullContent.toString();   // 返回完整文本，存 messages 表
    }

    private String buildUrl() {
        String url = configService.get("llm.base_url", llmConfig.getBaseUrl());
        if (!url.endsWith("/")) url += "/";
        return url + "chat/completions";
    }

    /**
     * stream 参数是唯一的开关：
     *   false → LLM 全部写完才回，Response 是一段完整 JSON
     *   true  → LLM 每算出一个 token 就丢一个 SSE event，边想边说
     */
    private HttpEntity<Map<String, Object>> buildRequest(List<Map<String, String>> messages, boolean stream) {
        return buildRequest(messages, stream, llmConfig.getMaxTokens());
    }

    private HttpEntity<Map<String, Object>> buildRequest(List<Map<String, String>> messages, boolean stream, int maxTokens) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", configService.get("llm.model", llmConfig.getModel()));
        body.put("messages", messages);
        body.put("max_tokens", maxTokens);
        body.put("temperature", llmConfig.getTemperature());
        body.put("stream", stream);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String apiKey = configService.get("llm.api_key", llmConfig.getApiKey());
        if (apiKey != null && !apiKey.isBlank()) {
            headers.setBearerAuth(apiKey);
        }
        return new HttpEntity<>(body, headers);
    }

    /** 递归检查异常链里是否包含 Premature EOF（DeepSeek 偶尔断流） */
    private static boolean isPrematureEof(Throwable e) {
        if (e == null) return false;
        if (e instanceof IOException && e.getMessage() != null
                && e.getMessage().contains("Premature EOF")) return true;
        return isPrematureEof(e.getCause());
    }

}

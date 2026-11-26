package com.novelagent.controller;

import com.novelagent.dto.ApiResponse;
import com.novelagent.service.DynamicConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/setup")
@RequiredArgsConstructor
public class SetupController {

    private final DynamicConfigService configService;

    /** GET /api/setup/status — 检查是否已配置 */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", configService.isConfigured());
        result.put("llmProvider", configService.get("llm.provider", ""));
        result.put("llmModel", configService.get("llm.model", System.getenv().getOrDefault("LLM_MODEL", "")));
        result.put("embedProvider", configService.get("embed.provider", ""));
        result.put("embedModel", configService.get("embed.model", System.getenv().getOrDefault("LLM_EMBED_MODEL", "")));
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /** POST /api/setup/save — 保存配置 */
    @PostMapping("/save")
    public ResponseEntity<ApiResponse<Map<String, String>>> save(@RequestBody Map<String, String> body) {
        for (var entry : body.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isBlank()) {
                configService.set(entry.getKey(), entry.getValue());
            }
        }
        Map<String, String> result = new LinkedHashMap<>();
        result.put("status", "ok");
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}

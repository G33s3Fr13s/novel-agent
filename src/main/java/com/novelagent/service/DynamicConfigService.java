package com.novelagent.service;

import com.novelagent.entity.SystemConfig;
import com.novelagent.repository.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态配置：优先读数据库（引导向导写入），fallback 到环境变量。
 * 支持运行时更新，不需要重启。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicConfigService {

    private final SystemConfigRepository configRepo;

    /** 本地缓存，避免每次请求都查 DB */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String get(String key, String envFallback) {
        // 1. 查缓存
        String cached = cache.get(key);
        if (cached != null) return cached;

        // 2. 查数据库
        Optional<SystemConfig> db = configRepo.findById(key);
        if (db.isPresent()) {
            cache.put(key, db.get().getValue());
            return db.get().getValue();
        }

        // 3. fallback 到环境变量
        if (envFallback != null && !envFallback.isBlank()) {
            return envFallback;
        }
        return "";
    }

    public void set(String key, String value) {
        configRepo.save(SystemConfig.builder().key(key).value(value).build());
        cache.put(key, value);
        log.info("Config saved: {} = {}***", key, value.length() > 8 ? value.substring(0, 4) : "****");
    }

    public boolean isConfigured() {
        return !get("llm.api_key", System.getenv().getOrDefault("LLM_API_KEY", "")).isBlank();
    }
}

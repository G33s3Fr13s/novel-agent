package com.novelagent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存发布订阅：允许多个 SSE 客户端同时订阅同一个续写 job 的生成过程。
 * 客户端断开后自动清理，不影响后台生成。
 *
 * 设计：
 * - 每个 job 一个 StreamEntry，里面有一个或多个 SseEmitter
 * - 后台生成线程往 StreamEntry 里 push token
 * - 前端通过 GET /continue/stream/{jobId} 订阅
 * - 客户端断开 → 从 entry 移除 → 如果 entry 无人观看且生成了部分章节 → 通知生成线程可以暂停
 */
@Slf4j
@Component
public class JobStreamManager {

    private final Map<UUID, StreamEntry> streams = new ConcurrentHashMap<>();

    /** 为一个 job 创建订阅入口 */
    public StreamEntry create(UUID jobId) {
        StreamEntry entry = new StreamEntry(jobId);
        streams.put(jobId, entry);
        log.debug("Stream entry created: jobId={}", jobId);
        return entry;
    }

    /** 获取已有 entry，不存在返回 null */
    public StreamEntry get(UUID jobId) {
        return streams.get(jobId);
    }

    /** 移除 entry */
    public void remove(UUID jobId) {
        StreamEntry entry = streams.remove(jobId);
        if (entry != null) {
            entry.close();
        }
    }

    /** 添加一个 SSE 订阅者 */
    public void subscribe(UUID jobId, SseEmitter emitter) {
        StreamEntry entry = streams.get(jobId);
        if (entry != null) {
            entry.addEmitter(emitter);
        }
    }

    /**
     * 推送 token 到所有订阅者。如果无人订阅，返回 false。
     */
    public boolean push(UUID jobId, String event, Object data) {
        StreamEntry entry = streams.get(jobId);
        if (entry == null) return false;
        return entry.send(event, data);
    }

    /** 检查是否有活跃的订阅者 */
    public boolean hasSubscribers(UUID jobId) {
        StreamEntry entry = streams.get(jobId);
        return entry != null && entry.emitterCount() > 0;
    }

    /**
     * 一个 job 的发布订阅入口。
     */
    public static class StreamEntry {
        private final UUID jobId;
        private final List<SseEmitter> emitters = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean generating = true;

        public StreamEntry(UUID jobId) {
            this.jobId = jobId;
        }

        public void addEmitter(SseEmitter emitter) {
            emitters.add(emitter);
            emitter.onCompletion(() -> removeEmitter(emitter));
            emitter.onError(e -> removeEmitter(emitter));
            emitter.onTimeout(() -> removeEmitter(emitter));
        }

        public int emitterCount() {
            emitters.removeIf(e -> {
                try { e.send(SseEmitter.event().comment("ping")); return false; }
                catch (Exception ex) { return true; }
            });
            return emitters.size();
        }

        public boolean send(String event, Object data) {
            if (emitters.isEmpty()) return false;
            boolean anySucceeded = false;
            synchronized (emitters) {
                Iterator<SseEmitter> it = emitters.iterator();
                while (it.hasNext()) {
                    SseEmitter e = it.next();
                    try {
                        e.send(SseEmitter.event().name(event).data(data));
                        anySucceeded = true;
                    } catch (IOException ex) {
                        it.remove();
                        log.debug("Removed dead emitter for job {}", jobId);
                    }
                }
            }
            return anySucceeded;
        }

        public boolean isGenerating() { return generating; }
        public void setGenerating(boolean g) { this.generating = g; }

        private void removeEmitter(SseEmitter emitter) {
            emitters.remove(emitter);
            log.debug("Emitter removed from job {} ({} remaining)", jobId, emitters.size());
        }

        public void close() {
            generating = false;
            synchronized (emitters) {
                for (SseEmitter e : emitters) {
                    try { e.complete(); } catch (Exception ignored) { }
                }
                emitters.clear();
            }
        }
    }
}

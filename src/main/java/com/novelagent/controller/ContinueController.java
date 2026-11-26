package com.novelagent.controller;

import com.novelagent.dto.ApiResponse;
import com.novelagent.dto.ContinueRequest;
import com.novelagent.entity.User;
import com.novelagent.repository.NovelRepository;
import com.novelagent.repository.UserRepository;
import com.novelagent.service.ContinueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/continue")
@RequiredArgsConstructor
public class ContinueController {

    private final ContinueService continueService;
    private final UserRepository userRepository;
    private final NovelRepository novelRepository;

    /** POST /api/continue/start — 启动续写 → 返回 jobId */
    @PostMapping("/start")
    public ResponseEntity<ApiResponse<Map<String, Object>>> start(
            @Valid @RequestBody ContinueRequest request) {
        User user = requireAuthAndOwnNovel(request.getNovelId());
        UUID novelId = UUID.fromString(request.getNovelId());
        List<UUID> chapterIds = request.getChapterIds() != null
                ? request.getChapterIds().stream().map(UUID::fromString).toList()
                : null;

        Map<String, Object> result = continueService.startGeneration(novelId, user.getId(), chapterIds,
                request.getForeword(), request.getAfterword(), request.getPrompt(),
                Math.max(1, Math.min(5, request.getChapterCount())),
                request.getSearchSites(), request.getSearchHint());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /** GET /api/continue/stream/{jobId} — 订阅指定 job 的 SSE 流 */
    @GetMapping(value = "/stream/{jobId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String jobId) {
        return continueService.subscribeStream(UUID.fromString(jobId), 300_000L);
    }

    /** POST /api/continue/resume/{jobId} — 继续未完成的生成 */
    @PostMapping("/resume/{jobId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resume(
            @PathVariable String jobId,
            @AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepository.findByUsername(userDetails.getUsername()).orElseThrow();
        Map<String, Object> result = continueService.resumeJob(UUID.fromString(jobId), user.getId());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /** GET /api/continue/jobs — 续写作业记录列表（分页） */
    @GetMapping("/jobs")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listJobs(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        User user = userRepository.findByUsername(userDetails.getUsername()).orElseThrow();
        return ResponseEntity.ok(ApiResponse.success(continueService.listJobs(user.getId(), page, size)));
    }

    /** GET /api/continue/jobs/{id} — 查看某次作业的配置和结果 */
    @GetMapping("/jobs/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getJob(
            @PathVariable String id,
            @AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepository.findByUsername(userDetails.getUsername()).orElseThrow();
        return ResponseEntity.ok(ApiResponse.success(continueService.getJob(UUID.fromString(id), user.getId())));
    }

    /** GET /api/continue/active?novelId=xxx — 检查是否有进行中/暂停的任务 */
    @GetMapping("/active")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getActiveJob(
            @RequestParam String novelId,
            @AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepository.findByUsername(userDetails.getUsername()).orElseThrow();
        Map<String, Object> active = continueService.getActiveJob(UUID.fromString(novelId), user.getId());
        return ResponseEntity.ok(ApiResponse.success(active));
    }

    private User requireAuthAndOwnNovel(String novelId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof UserDetails)) {
            throw new RuntimeException("使用续写功能需要先登录");
        }
        User user = userRepository.findByUsername(((UserDetails) auth.getPrincipal()).getUsername())
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        UUID nid = UUID.fromString(novelId);
        if (novelRepository.findByIdAndUserId(nid, user.getId()).isEmpty()) {
            throw new RuntimeException("无权访问该小说或小说不存在");
        }
        return user;
    }
}

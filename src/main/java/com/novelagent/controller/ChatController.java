package com.novelagent.controller;

import com.novelagent.dto.ApiResponse;
import com.novelagent.dto.ChatRequest;
import com.novelagent.dto.ChatResponse;
import com.novelagent.entity.User;
import com.novelagent.repository.UserRepository;
import com.novelagent.repository.NovelRepository;
import com.novelagent.service.ChatService;
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

// DeleteMapping is mapped by the wildcard import above
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final UserRepository userRepository;
    private final NovelRepository novelRepository;

    /**
     * POST /api/chat (non-streaming) — works for both anonymous & authenticated.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<ChatResponse>> chat(
            @Valid @RequestBody ChatRequest request
    ) {
        User user = currentUser();
        if (request.getNovelId() != null && !request.getNovelId().isBlank()) {
            user = requireAuthAndOwnNovel(user, request.getNovelId());
        }
        if (user != null) {
            return ResponseEntity.ok(ApiResponse.success(chatService.chat(request, user)));
        } else {
            return ResponseEntity.ok(ApiResponse.success(chatService.chatAnonymous(request)));
        }
    }

    /**
     * POST /api/chat/stream — SSE typewriter, anonymous & authenticated.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(120_000L);
        User user = currentUser();
        if (request.getNovelId() != null && !request.getNovelId().isBlank()) {
            user = requireAuthAndOwnNovel(user, request.getNovelId());
        }

        if (user != null) {
            Map<String, Object> ctx = chatService.prepareStream(request, user);
            chatService.executeStreamAsync(ctx, emitter);
        } else {
            chatService.streamAnonymous(request, emitter);
        }
        return emitter;
    }

    @GetMapping("/conversations")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getConversations(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        User user = userRepository.findByUsername(userDetails.getUsername()).orElseThrow();
        return ResponseEntity.ok(ApiResponse.success(chatService.getConversations(user)));
    }

    @GetMapping("/conversations/{id}")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMessages(
            @PathVariable String id,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        User user = userRepository.findByUsername(userDetails.getUsername()).orElseThrow();
        return ResponseEntity.ok(ApiResponse.success(chatService.getMessages(UUID.fromString(id), user)));
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteConversation(
            @PathVariable String id,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        User user = userRepository.findByUsername(userDetails.getUsername()).orElseThrow();
        chatService.deleteConversation(UUID.fromString(id), user);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PutMapping("/conversations/{id}")
    public ResponseEntity<ApiResponse<Void>> renameConversation(
            @PathVariable String id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        User user = userRepository.findByUsername(userDetails.getUsername()).orElseThrow();
        chatService.renameConversation(UUID.fromString(id), body.get("title"), user);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> searchMessages(
            @RequestParam String q,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        User user = userRepository.findByUsername(userDetails.getUsername()).orElseThrow();
        return ResponseEntity.ok(ApiResponse.success(chatService.searchMessages(q, user)));
    }

    /**
     * Returns the authenticated User, or null if anonymous.
     */
    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof UserDetails) {
            return userRepository.findByUsername(((UserDetails) auth.getPrincipal()).getUsername()).orElse(null);
        }
        return null;
    }

    /** 资料库问答必须登录，且只能查自己的小说 */
    private User requireAuthAndOwnNovel(User user, String novelId) {
        if (user == null) {
            throw new RuntimeException("使用资料库问答需要先登录");
        }
        UUID nid = UUID.fromString(novelId);
        if (novelRepository.findByIdAndUserId(nid, user.getId()).isEmpty()) {
            throw new RuntimeException("无权访问该小说或小说不存在");
        }
        return user;
    }
}

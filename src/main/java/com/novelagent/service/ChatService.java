package com.novelagent.service;

import com.novelagent.dto.ChatRequest;
import com.novelagent.dto.ChatResponse;
import com.novelagent.entity.Conversation;
import com.novelagent.entity.Message;
import com.novelagent.entity.User;
import com.novelagent.repository.ConversationRepository;
import com.novelagent.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final LlmService llmService;
    private final RetrievalService retrievalService;
    private final PlotBuildService plotBuildService;
    private final WebSearchService webSearchService;

    private static final int MAX_CONVERSATIONS = 5;
    private static final int WINDOW_SIZE = 12;  // 超过此数触发压缩，保留最近 10 条
    private static final int KEEP_RECENT = 10;

    private static final String SYSTEM_PROMPT = "你是一个专业的网络小说智能续写助手。你熟悉各种小说类型（玄幻、都市、科幻、历史、悬疑等），擅长分析人物关系、情节走向和写作风格，能够帮助作者延续小说的故事脉络。";

    private static final String RETRIEVAL_SYSTEM_PROMPT = "你是一个专业的网络小说智能续写助手。以下是从小说中检索到的相关段落作为参考资料，请根据这些资料回答用户的问题。如果资料不足以回答，请诚实说明，并结合你的知识给出建议。";

    /**
     * Non-streaming chat.
     */
    @Transactional
    public ChatResponse chat(ChatRequest request, User user) {
        Conversation conversation = getOrCreateConversation(request.getConversationId(), user);
        saveUserMessage(conversation, request.getMessage());
        List<Map<String, String>> llmMessages = buildLlmMessagesInternal(conversation.getId(),
                request.getNovelId(), request.getChapterId(), request.getSearchSites());
        String reply = llmService.chat(llmMessages);
        Message saved = saveAssistantMessage(conversation, reply);
        maybeSetTitle(conversation, request);
        return ChatResponse.of(conversation.getId(), reply, saved.getCreatedAt());
    }

    // ─── Anonymous chat ───────────────────────────────────────

    /**
     * Anonymous non-streaming — no DB.
     * Uses frontend-provided history if available, otherwise single-turn.
     */
    public ChatResponse chatAnonymous(ChatRequest request) {
        List<Map<String, String>> llmMessages = buildAnonymousMessages(request);
        String reply = llmService.chat(llmMessages);
        return ChatResponse.of(UUID.randomUUID(), reply, java.time.Instant.now());
    }

    /**
     * Anonymous streaming — no DB.
     */
    @Async("streamExecutor")
    public void streamAnonymous(ChatRequest request, SseEmitter emitter) {
        try {
            List<Map<String, String>> llmMessages = buildAnonymousMessages(request);
            String fullReply = llmService.chatStream(llmMessages, token -> {
                try {
                    String safe = token.replace("\\", "\\\\").replace("\n", "\\n");
                    emitter.send(SseEmitter.event().name("token").data(safe));
                } catch (Exception e) {
                    log.warn("Failed to send SSE token", e);
                }
            });
            log.info("=== Stream answer ===\n{}", fullReply);
            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(Map.of("conversationId", UUID.randomUUID().toString())));
            emitter.complete();
        } catch (Exception e) {
            log.error("Anonymous streaming failed", e);
            try {
                emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
            } catch (Exception ignored) {}
            emitter.completeWithError(e);
        }
    }

    private List<Map<String, String>> buildAnonymousMessages(ChatRequest request) {
        List<Map<String, String>> messages = new ArrayList<>();

        // 有 novelId → 双路检索 + 剧情大纲 + 注入上下文
        String retrievalContext = null;
        if (request.getNovelId() != null && !request.getNovelId().isBlank()) {
            UUID nid = UUID.fromString(request.getNovelId());
            UUID cid = request.getChapterId() != null && !request.getChapterId().isBlank()
                    ? UUID.fromString(request.getChapterId()) : null;
            var result = retrievalService.retrieve(nid, cid, request.getMessage());
            String outline = plotBuildService.toOutlineText(nid);
            retrievalContext = result.toContextString();
            if (!outline.isEmpty()) {
                retrievalContext = outline + "\n\n" + retrievalContext;
            }
            log.info("Retrieval: {} fused chunks (vector={}, graph={})",
                    result.fused().size(), result.vectorOnly().size(), result.graphOnly().size());
        }

        // 联网搜索（匿名用户也支持）
        String searchContext = null;
        List<String> searchSites = request.getSearchSites();
        if (searchSites != null && !searchSites.isEmpty()) {
            String query = request.getMessage();
            if (request.getHistory() != null && !request.getHistory().isEmpty()) {
                // 取最后一条 user 消息作为搜索查询
                query = request.getHistory().stream()
                        .filter(m -> "user".equals(m.getRole()))
                        .reduce((first, last) -> last)
                        .map(ChatRequest.MessageItem::getContent)
                        .orElse(request.getMessage());
            }
            if (query != null && !query.isBlank()) {
                String searchQuery = webSearchService.optimizeQuery(query.strip());
                var searchResults = webSearchService.search(searchQuery, searchSites);
                if (searchResults.isEmpty() && !searchSites.isEmpty()) {
                    log.info("Site-filtered search empty, retrying without site filter");
                    searchResults = webSearchService.search(searchQuery, null);
                }
                searchContext = webSearchService.toContextString(searchResults);
            }
        }

        // 合并所有上下文
        StringBuilder contextBuilder = new StringBuilder();
        if (retrievalContext != null && !retrievalContext.isEmpty()) {
            contextBuilder.append(retrievalContext);
        }
        if (searchContext != null && !searchContext.isEmpty()) {
            if (contextBuilder.length() > 0) contextBuilder.append("\n\n");
            contextBuilder.append(searchContext);
        }
        String combinedContext = contextBuilder.toString();

        String systemPrompt = !combinedContext.isEmpty()
                ? RETRIEVAL_SYSTEM_PROMPT + "\n\n参考资料：\n" + combinedContext
                : SYSTEM_PROMPT;
        messages.add(Map.of("role", "system", "content", systemPrompt));

        // 如果前端传了历史消息，拼进去
        if (request.getHistory() != null) {
            for (var item : request.getHistory()) {
                messages.add(Map.of("role", item.getRole(), "content", item.getContent()));
            }
        }
        messages.add(Map.of("role", "user", "content", request.getMessage()));
        return messages;
    }

    // ─── Authenticated chat ───────────────────────────────────

    /**
     * Prepare context, save user message, then hand off to async streaming.
     */
    @Transactional
    public Map<String, Object> prepareStream(ChatRequest request, User user) {
        Conversation conversation = getOrCreateConversation(request.getConversationId(), user);
        saveUserMessage(conversation, request.getMessage());
        maybeSetTitle(conversation, request);
        List<Map<String, String>> llmMessages = buildLlmMessagesInternal(conversation.getId(),
                request.getNovelId(), request.getChapterId(), request.getSearchSites());
        conversation.getMessages().clear(); // detach before passing to async

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("conversation", conversation);
        ctx.put("llmMessages", llmMessages);
        return ctx;
    }

    /**
     * Execute streaming LLM call in async thread pool, sending tokens via SseEmitter.
     * Called by @Async — this is the work that runs on the streamExecutor thread pool.
     */
    @Async("streamExecutor")
    public void executeStreamAsync(Map<String, Object> ctx, SseEmitter emitter) {
        Conversation conversation = (Conversation) ctx.get("conversation");
        List<Map<String, String>> llmMessages = (List<Map<String, String>>) ctx.get("llmMessages");

        try {
            String fullReply = llmService.chatStream(llmMessages, token -> {
                try {
                    String safe = token.replace("\\", "\\\\").replace("\n", "\\n");
                    emitter.send(SseEmitter.event().name("token").data(safe));
                } catch (Exception e) {
                    log.warn("Failed to send SSE token", e);
                }
            });
            log.info("=== Stream answer ===\n{}", fullReply);

            // Save assistant reply after stream completes
            saveAssistantMessageAsync(conversation.getId(), fullReply);

            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(Map.of("conversationId", conversation.getId().toString())));
            emitter.complete();
        } catch (Exception e) {
            log.error("Streaming failed", e);
            try {
                emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
            } catch (Exception ignored) {}
            emitter.completeWithError(e);
        }
    }

    @Transactional
    protected void saveAssistantMessageAsync(UUID conversationId, String content) {
        Conversation conv = conversationRepository.findById(conversationId).orElseThrow();
        messageRepository.save(Message.builder()
                .conversation(conv)
                .role(Message.Role.ASSISTANT.value())
                .content(content)
                .build());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getConversations(User user) {
        return conversationRepository.findByUserIdOrderByUpdatedAtDesc(user.getId()).stream()
                .map(c -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", c.getId().toString());
                    map.put("title", c.getTitle());
                    map.put("createdAt", c.getCreatedAt());
                    map.put("updatedAt", c.getUpdatedAt());
                    return map;
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getMessages(UUID conversationId, User user) {
        Conversation conv = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new RuntimeException("会话不存在"));
        if (!conv.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("无权访问该会话");
        }
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .map(m -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", m.getId().toString());
                    map.put("role", m.getRole());
                    map.put("content", m.getContent());
                    map.put("createdAt", m.getCreatedAt());
                    return map;
                })
                .collect(Collectors.toList());
    }

    @Transactional
    public void renameConversation(UUID conversationId, String title, User user) {
        Conversation conv = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new RuntimeException("会话不存在"));
        if (!conv.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("无权操作");
        }
        conv.setTitle(title);
        conversationRepository.save(conv);
    }

    @Transactional
    public void deleteConversation(UUID conversationId, User user) {
        Conversation conv = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new RuntimeException("会话不存在"));
        if (!conv.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("无权操作");
        }
        conversationRepository.delete(conv);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> searchMessages(String keyword, User user) {
        // 先查标题匹配的会话
        List<Conversation> titleMatches = conversationRepository
                .findByUserIdAndTitleContainingIgnoreCaseOrderByUpdatedAtDesc(user.getId(), keyword);

        // 再查内容匹配的消息
        List<Message> contentMatches = messageRepository.searchByContent(user.getId(), keyword);

        // 合并结果：按会话去重，每个会话取最佳匹配片段
        Map<UUID, Map<String, Object>> resultMap = new LinkedHashMap<>();

        for (Conversation c : titleMatches) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("conversationId", c.getId().toString());
            map.put("title", c.getTitle() != null ? c.getTitle() : "新对话");
            map.put("snippet", "标题匹配");
            map.put("matchType", "title");
            map.put("updatedAt", c.getUpdatedAt());
            resultMap.put(c.getId(), map);
        }

        for (Message m : contentMatches) {
            UUID cid = m.getConversation().getId();
            if (!resultMap.containsKey(cid)) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("conversationId", cid.toString());
                map.put("title", m.getConversation().getTitle() != null
                        ? m.getConversation().getTitle() : "新对话");
                // 提取匹配关键词附近的片段
                String snippet = extractSnippet(m.getContent(), keyword, 80);
                map.put("snippet", snippet);
                map.put("matchType", "content");
                map.put("updatedAt", m.getConversation().getUpdatedAt());
                resultMap.put(cid, map);
            }
        }

        return new ArrayList<>(resultMap.values());
    }

    private String extractSnippet(String content, String keyword, int radius) {
        int idx = content.toLowerCase().indexOf(keyword.toLowerCase());
        if (idx < 0) {
            return content.length() > 200 ? content.substring(0, 200) + "..." : content;
        }
        int start = Math.max(0, idx - radius);
        int end = Math.min(content.length(), idx + keyword.length() + radius);
        String snippet = (start > 0 ? "..." : "") + content.substring(start, end) + (end < content.length() ? "..." : "");
        return snippet;
    }

    // ─── internal helpers ──────────────────────────────────

    @Transactional
    protected Conversation getOrCreateConversation(String conversationId, User user) {
        if (conversationId != null && !conversationId.isBlank()) {
            UUID id = UUID.fromString(conversationId);
            Conversation conv = conversationRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("会话不存在"));
            if (!conv.getUser().getId().equals(user.getId())) {
                throw new RuntimeException("无权访问该会话");
            }
            return conv;
        }
        // 限制每个用户最多 5 个会话，超出删最旧的
        List<Conversation> userConvs = conversationRepository.findByUserIdOrderByUpdatedAtDesc(user.getId());
        if (userConvs.size() >= MAX_CONVERSATIONS) {
            for (int i = MAX_CONVERSATIONS - 1; i < userConvs.size(); i++) {
                conversationRepository.delete(userConvs.get(i));
                log.info("Deleted old conversation: id={}", userConvs.get(i).getId());
            }
        }
        return conversationRepository.save(Conversation.builder().user(user).build());
    }

    @Transactional
    protected void saveUserMessage(Conversation conversation, String content) {
        messageRepository.save(Message.builder()
                .conversation(conversation)
                .role(Message.Role.USER.value())
                .content(content)
                .build());
    }

    @Transactional
    protected Message saveAssistantMessage(Conversation conversation, String content) {
        return messageRepository.save(Message.builder()
                .conversation(conversation)
                .role(Message.Role.ASSISTANT.value())
                .content(content)
                .build());
    }

    @Transactional
    protected void maybeSetTitle(Conversation conversation, ChatRequest request) {
        if (conversation.getTitle() == null && request.getConversationId() == null) {
            String title = request.getMessage().length() > 30
                    ? request.getMessage().substring(0, 30) + "..."
                    : request.getMessage();
            conversation.setTitle(title);
            conversationRepository.save(conversation);
        }
    }

    private List<Map<String, String>> buildLlmMessages(UUID conversationId, String novelId, String chapterId) {
        return buildLlmMessagesInternal(conversationId, novelId, chapterId, null);
    }

    private List<Map<String, String>> buildLlmMessagesInternal(UUID conversationId, String novelId,
            String chapterId, List<String> searchSites) {
        List<Message> history = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        List<Map<String, String>> messages = new ArrayList<>();

        // 有 novelId → 双路检索
        String retrievalContext = null;
        if (novelId != null && !novelId.isBlank()) {
            String query = history.stream()
                    .filter(m -> Message.Role.USER.value().equals(m.getRole()))
                    .reduce((first, last) -> last)
                    .map(Message::getContent)
                    .orElse("");
            if (!query.isBlank()) {
                var result = retrievalService.retrieve(UUID.fromString(novelId), chapterId != null && !chapterId.isBlank() ? UUID.fromString(chapterId) : null, query);
                String outline = plotBuildService.toOutlineText(UUID.fromString(novelId));
                retrievalContext = result.toContextString();
                if (!outline.isEmpty()) {
                    retrievalContext = outline + "\n\n" + retrievalContext;
                }
                log.info("Retrieval: {} fused chunks (vector={}, graph={})",
                        result.fused().size(), result.vectorOnly().size(), result.graphOnly().size());
            }
        }

        // 联网搜索
        String searchContext = null;
        if (searchSites != null && !searchSites.isEmpty()) {
            String query = history.stream()
                    .filter(m -> Message.Role.USER.value().equals(m.getRole()))
                    .reduce((first, last) -> last)
                    .map(Message::getContent)
                    .orElse("");
            if (!query.isBlank()) {
                String searchQuery = webSearchService.optimizeQuery(query.strip());
                var searchResults = webSearchService.search(searchQuery, searchSites);
                if (searchResults.isEmpty() && searchSites != null && !searchSites.isEmpty()) {
                    log.info("Site-filtered search empty, retrying without site filter");
                    searchResults = webSearchService.search(searchQuery, null);
                }
                searchContext = webSearchService.toContextString(searchResults);
            }
        }

        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append(retrievalContext != null && !retrievalContext.isEmpty()
                ? RETRIEVAL_SYSTEM_PROMPT + "\n\n参考资料：\n" + retrievalContext
                : SYSTEM_PROMPT);
        if (searchContext != null && !searchContext.isEmpty()) {
            promptBuilder.append("\n\n").append(searchContext);
        }
        messages.add(Map.of("role", "system", "content", promptBuilder.toString()));

        // ── 滑动窗口压缩：超过阈值时压缩旧消息 ──
        if (history.size() > WINDOW_SIZE) {
            int oldCount = history.size() - KEEP_RECENT;
            List<Message> oldMessages = history.subList(0, oldCount);
            List<Message> recentMessages = history.subList(oldCount, history.size());

            // 检查是否已有压缩摘要，有则跳过
            boolean hasSummary = oldMessages.size() == 1
                    && Message.Role.SYSTEM.value().equals(oldMessages.get(0).getRole())
                    && oldMessages.get(0).getContent().startsWith("[对话摘要]");

            if (!hasSummary) {
                String summary = compressHistory(oldMessages);
                // 将旧消息替换为一条系统摘要（删旧 + 插新）
                for (int i = 0; i < oldCount; i++) {
                    messageRepository.delete(oldMessages.get(i));
                }
                messageRepository.save(Message.builder()
                        .conversation(conversationRepository.findById(conversationId).orElse(null))
                        .role(Message.Role.SYSTEM.value())
                        .content("[对话摘要] " + summary)
                        .build());
                log.info("Compressed {} old messages into summary for conversation {}", oldCount, conversationId);
            }
        }

        // 重新加载（压缩后可能有变化）
        List<Message> finalHistory = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        List<Message> recent = finalHistory.size() > KEEP_RECENT + 1
                ? finalHistory.subList(finalHistory.size() - KEEP_RECENT, finalHistory.size())
                : finalHistory;
        for (Message msg : recent) {
            messages.add(Map.of("role", msg.getRole(), "content", msg.getContent()));
        }
        return messages;
    }

    /** 将旧消息压缩为一句话摘要 */
    private String compressHistory(List<Message> oldMessages) {
        StringBuilder sb = new StringBuilder();
        for (Message m : oldMessages) {
            sb.append(m.getRole()).append(": ").append(m.getContent());
            if (m.getContent().length() > 200) {
                sb.setLength(sb.length() - m.getContent().length() + 200);
                sb.append("...");
            }
            sb.append("\n");
        }
        try {
            List<Map<String, String>> msgs = List.of(
                    Map.of("role", "system", "content",
                            "请用2-3句中文总结以下对话的核心内容，只输出总结，不超过80字。"),
                    Map.of("role", "user", "content", sb.toString())
            );
            return llmService.chat(msgs, 200);
        } catch (Exception e) {
            log.warn("History compression failed: {}", e.getMessage());
            return "用户和助手讨论了小说创作相关话题。";
        }
    }
}

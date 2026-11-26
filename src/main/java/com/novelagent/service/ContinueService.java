package com.novelagent.service;

import com.novelagent.entity.Chapter;
import com.novelagent.entity.ContinueJob;
import com.novelagent.entity.GraphEntity;
import com.novelagent.entity.Novel;
import com.novelagent.entity.User;
import com.novelagent.repository.ChapterRepository;
import com.novelagent.repository.ContinueJobRepository;
import com.novelagent.entity.GraphRelationship;
import com.novelagent.repository.GraphEntityRepository;
import com.novelagent.repository.GraphRelationshipRepository;
import com.novelagent.repository.NovelRepository;
import com.novelagent.repository.UserRepository;
import com.novelagent.service.RetrievalService.RetrievalResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.stream.Stream;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContinueService {

    private final NovelRepository novelRepository;
    private final ChapterRepository chapterRepository;
    private final GraphEntityRepository graphEntityRepo;
    private final GraphRelationshipRepository graphRelationRepo;
    private final ContinueJobRepository continueJobRepo;
    private final UserRepository userRepository;
    private final LlmService llmService;
    private final RetrievalService retrievalService;
    private final PlotBuildService plotBuildService;
    private final JobStreamManager streamManager;
    private final WebSearchService webSearchService;

    @Lazy @Autowired
    private ContinueService self;  // 自注入代理，解决 @Async 自调用失效

    private static final String SYSTEM_PROMPT = """
            你是一个专业的小说续写助手。请根据提供的剧情大纲、前文内容、参考资料和风格样本，续写小说内容。

            【格式要求 — 必须严格遵守】
            1. 每一章都必须以 "第X章 章节标题" 作为章节标题，标题单独成行、醒目突出
               - 生成多章时，每章都要有自己的标题，不可省略任何一章的标题
               - 章节编号必须连续（如第14章、第15章、第16章）
               - 标题要与该章内容相关，起到点睛作用
            2. 段落之间必须用空行分隔（即两个换行符\\n\\n），每段 3-8 句为宜
            3. 对话单独成段，用中文引号""
            4. 每章 2500-4000 字

            【叙事要求】
            1. 优先根据【参考片段】中双路检索召回的内容特征，判断最合适的叙事方式：
               - 如果检索结果集中在单一人物视角 → 线性叙事续接
               - 如果检索结果分散在多个人物之间 → 群像/多视角切换
               - 如果检索结果包含时间线跳跃或因果回溯 → 插叙/倒叙
               - 如果检索结果涉及多条独立剧情线 → 并线叙事
            2. 根据以上判断，在以下叙事方式中选择最合适的：
               - 可以从上一章结尾处直接续接（线性叙事）
               - 可以切换到其他人物的视角（多主角/群像切换）
               - 可以插入回忆或往事片段（插叙/倒叙）
               - 可以展开另一条并行故事线（并线叙事）
            3. 不同的叙事线之间用空行或明显的场景分隔标记区分
            4. 保持与风格参考一致的语言风格、叙事节奏和用词习惯
            5. 遵循剧情大纲的整体走向，不要偏离主线
            6. 不要引入与人物关系矛盾的新设定
            7. 情节推进合理，有起承转合

            记住：段落之间留空行，这是硬性要求。没有空行的长文本不是小说。
            """;

    // ═══════════════════════════════════════════════════════════════
    // 方案 B：任务表驱动的异步生成
    // ═══════════════════════════════════════════════════════════════

    /**
     * 启动续写：创建 PENDING 作业 → 异步生成 → 返回 jobId。
     * 前端用 GET /continue/stream/{jobId} 订阅 SSE 流。
     */
    @Transactional
    public Map<String, Object> startGeneration(UUID novelId, UUID userId, List<UUID> chapterIds,
                                                String foreword, String afterword, String prompt,
                                                int chapterCount, List<String> searchSites, String searchHint) {
        UUID jobId = createGeneratingJob(novelId, userId, chapterIds, foreword, afterword, prompt, chapterCount, searchSites, searchHint);
        // 通过代理调用，确保 @Async 生效
        self.generateJobAsync(jobId, novelId, chapterIds, foreword, afterword, prompt, chapterCount, searchSites, searchHint);
        return Map.of("jobId", jobId.toString());
    }

    /** 订阅 job 的 SSE 流。可在生成过程中任意时刻连接/断开。 */
    public SseEmitter subscribeStream(UUID jobId, long timeout) {
        SseEmitter emitter = new SseEmitter(timeout);
        streamManager.subscribe(jobId, emitter);
        return emitter;
    }

    /** 断线后继续：从 PARTIAL 状态的 job 接着生成剩余章节 */
    @Transactional
    public Map<String, Object> resumeJob(UUID jobId, UUID userId) {
        ContinueJob job = continueJobRepo.findById(jobId)
                .orElseThrow(() -> new RuntimeException("作业记录不存在"));
        if (!job.getUser().getId().equals(userId)) throw new RuntimeException("无权访问");
        if (!"PARTIAL".equals(job.getStatus()) && !"PENDING".equals(job.getStatus())) {
            throw new RuntimeException("只能恢复未完成的作业");
        }
        // 解析已生成的章节数和剩余章节
        String existingResult = job.getResult() != null ? job.getResult() : "";
        int completedChapters = countChapters(existingResult);
        int remaining = job.getChapterCount() - completedChapters;
        if (remaining <= 0) {
            // 已经全生成完了，直接标完成
            job.setStatus("COMPLETED");
            continueJobRepo.save(job);
            return Map.of("jobId", jobId.toString(), "status", "COMPLETED", "message", "已全部完成");
        }
        job.setStatus("GENERATING");
        continueJobRepo.save(job);

        List<UUID> chapterIds = job.getChapterIds() != null
                ? Arrays.stream(job.getChapterIds().split(","))
                    .filter(s -> !s.isBlank()).map(UUID::fromString).toList()
                : null;

        List<String> searchSites = job.getSearchSites() != null
                ? Arrays.stream(job.getSearchSites().split(","))
                    .filter(s -> !s.isBlank()).toList()
                : null;

        self.generateJobAsync(jobId, UUID.fromString(job.getNovel().getId().toString()),
                chapterIds, job.getForeword(), job.getAfterword(), job.getPrompt(), remaining,
                searchSites, job.getSearchHint());
        return Map.of("jobId", jobId.toString(), "resumed", true, "remaining", remaining);
    }

    /**
     * 异步生成入口（@Async 代理需要 public 方法）。
     * 加 @Transactional 确保异步线程有独立的持久化上下文。
     */
    @Async("streamExecutor")
    @Transactional
    public void generateJobAsync(UUID jobId, UUID novelId, List<UUID> chapterIds,
                                  String foreword, String afterword, String prompt,
                                  int chapterCount, List<String> searchSites, String searchHint) {
        generateJob(jobId, novelId, chapterIds, foreword, afterword, prompt, chapterCount, searchSites, searchHint);
    }

    /**
     * 核心生成逻辑：逐章调用 LLM，推送到 JobStreamManager。
     * 客户端断开后，当前章写完即暂停（标记 PARTIAL），不浪费 API 费用。
     */
    private void generateJob(UUID jobId, UUID novelId, List<UUID> chapterIds,
                             String foreword, String afterword, String prompt,
                             int chapterCount, List<String> searchSites, String searchHint) {
        JobStreamManager.StreamEntry entry = streamManager.create(jobId);
        try {
            // ── 准备上下文（复用现有方法） ──
            List<Chapter> selectedChapters = resolveChapters(novelId, chapterIds);
            String outline = plotBuildService.toOutlineText(novelId);
            String retrievalContext = buildRetrievalContext(novelId, chapterIds, foreword, afterword, prompt);
            String styleSamples = buildStyleSamples(selectedChapters);
            String entitySummary = buildEntitySummary(novelId, retrievalContext);

            // 联网搜索（一次搜索，所有章节共享）
            String searchContext = buildSearchContext(foreword, afterword, prompt, searchSites, searchHint);

            // 起始章节号：已有结果则从下一个开始，否则从选中章节末尾+1
            ContinueJob job = continueJobRepo.findById(jobId).orElse(null);
            String existingResult = job != null && job.getResult() != null ? job.getResult() : "";
            int completedChapters = countChapters(existingResult);
            int baseChapterNum = selectedChapters.stream()
                    .mapToInt(Chapter::getChapterNum).max().orElse(0) + 1;
            int startChapterNum = baseChapterNum + completedChapters;

            StringBuilder allContent = new StringBuilder(existingResult);
            String lastChapterContent = extractLastChapter(existingResult);

            // 前情提要（B 方案核心：滑动窗口 + 摘要）
            List<String> chapterSummaries = new ArrayList<>();

            for (int i = 0; i < chapterCount; i++) {
                int chapterNum = startChapterNum + i;

                log.info("=== Generating chapter {} ({}/{}) ===", chapterNum, i + 1, chapterCount);

                // 组装上下文：锚定 + 前情提要
                String anchor;
                if (lastChapterContent != null && !lastChapterContent.isBlank()) {
                    anchor = "上一章末尾：\n\n" + extractTail(lastChapterContent, 1500);
                } else {
                    anchor = buildAnchorText(selectedChapters);
                }

                // 添加前情提要
                StringBuilder contextExtra = new StringBuilder(anchor);
                if (!chapterSummaries.isEmpty()) {
                    contextExtra.append("\n\n【前情提要】\n");
                    for (int s = Math.max(0, chapterSummaries.size() - 3); s < chapterSummaries.size(); s++) {
                        contextExtra.append("- 第").append(startChapterNum + s).append("章：")
                                .append(chapterSummaries.get(s)).append("\n");
                    }
                }

                String userPrompt = buildBatchUserPrompt(outline, contextExtra.toString(),
                        retrievalContext, styleSamples, entitySummary,
                        foreword, afterword, prompt, 1, chapterNum, searchContext);

                List<Map<String, String>> llmMessages = new ArrayList<>();
                llmMessages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
                llmMessages.add(Map.of("role", "user", "content", userPrompt));

                // 用 JobStreamManager 推送 token
                int maxTokens = Math.min(8192, 7000);
                String chapterText = llmService.chatStream(llmMessages, token -> {
                    String safe = token.replace("\\", "\\\\").replace("\n", "\\n");
                    entry.send("t", safe);
                }, maxTokens);

                // 累积结果并增量保存
                if (allContent.length() > 0) allContent.append("\n\n");
                allContent.append(chapterText);
                lastChapterContent = chapterText;
                updateJobResultIncremental(jobId, allContent.toString());

                // 提取本章摘要（B 方案）
                String summary = summarizeChapter(chapterText);
                if (summary != null && !summary.isBlank()) {
                    chapterSummaries.add(summary);
                }

                // 通知前端本章完成
                var chDone = new java.util.LinkedHashMap<String, Object>();
                chDone.put("chapter", chapterNum);
                chDone.put("completed", i + 1);
                chDone.put("total", chapterCount);
                entry.send("chapter", chDone);
            }

            // ── 全部完成 ──
            completeJob(jobId, allContent.toString());
            int actualChapters = countChapters(allContent.toString());
            var doneData = new java.util.LinkedHashMap<String, Object>();
            doneData.put("jobId", jobId.toString());
            doneData.put("length", allContent.length());
            doneData.put("actualChapters", actualChapters);
            doneData.put("expectedChapters", chapterCount + completedChapters);
            doneData.put("truncated", actualChapters < chapterCount + completedChapters);
            entry.send("done", doneData);
            entry.setGenerating(false);

        } catch (Exception e) {
            log.error("Generation failed for job {}", jobId, e);
            failJob(jobId);
            try { entry.send("error", e.getMessage()); } catch (Exception ignored) { }
            entry.setGenerating(false);
        } finally {
            // 延迟清理，给客户端时间收到最终事件
            new Thread(() -> {
                try { Thread.sleep(5000); } catch (InterruptedException ignored) { }
                streamManager.remove(jobId);
            }).start();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 摘要提取（B 方案）
    // ═══════════════════════════════════════════════════════════════

    /** 从章节内容中提取一句话摘要 */
    private String summarizeChapter(String chapterText) {
        if (chapterText == null || chapterText.length() < 100) return null;
        try {
            // 只传前 2000 字给 LLM，足够提取摘要
            String excerpt = chapterText.length() > 2000
                    ? chapterText.substring(0, 2000) : chapterText;
            List<Map<String, String>> msgs = List.of(
                    Map.of("role", "system", "content",
                            "你是一个专业的小说编辑。请用一句中文（不超过50字）总结以下章节的关键剧情，包括：人物状态变化、重要事件、新出现的伏笔或冲突。只输出总结，不要加任何前缀。"),
                    Map.of("role", "user", "content", excerpt)
            );
            String summary = llmService.chat(msgs, 100);
            return summary != null ? summary.trim() : null;
        } catch (Exception e) {
            log.warn("Chapter summary failed: {}", e.getMessage());
            return null;
        }
    }

    /** 取最后一段章节的全文 */
    private String extractLastChapter(String fullText) {
        if (fullText == null || fullText.isBlank()) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\\s*(?:#+\\s*)?第\\d+章\\s+").matcher(fullText);
        int lastStart = -1;
        while (m.find()) lastStart = m.start();
        if (lastStart >= 0) {
            return fullText.substring(lastStart).trim();
        }
        return fullText;
    }

    /** 增量更新结果（每章完成后调用） */
    @Transactional
    public void updateJobResultIncremental(UUID jobId, String result) {
        continueJobRepo.findById(jobId).ifPresent(j -> {
            j.setResult(result);
            j.setResultLength(result.length());
            continueJobRepo.save(j);
        });
    }

    /** 标记为部分完成 */
    @Transactional
    public void updateJobPartial(UUID jobId, String partialResult) {
        continueJobRepo.findById(jobId).ifPresent(j -> {
            j.setResult(partialResult);
            j.setResultLength(partialResult.length());
            j.setStatus("PARTIAL");
            continueJobRepo.save(j);
            log.info("Job {} marked PARTIAL ({} chars, {} chapters)", jobId,
                    partialResult.length(), countChapters(partialResult));
        });
    }

    // ═══════════════════════════════════════════════════════════════
    // 上下文组装（保留原有方法）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 组装续写上下文消息列表。
     */
    public List<Map<String, String>> buildContinueMessages(UUID novelId, List<UUID> chapterIds,
                                                            int chapterCount,
                                                            String foreword, String afterword, String prompt) {
        // 如果没选章节，取全部章节
        List<Chapter> selectedChapters = resolveChapters(novelId, chapterIds);

        // ① 剧情大纲
        String outline = plotBuildService.toOutlineText(novelId);

        // ② 前文锚定：最近章节的末尾原文
        String anchor = buildAnchorText(selectedChapters);

        // ③ 双路检索片段
        String retrievalContext = buildRetrievalContext(novelId, chapterIds, foreword, afterword, prompt);

        // ④ 风格样本：从选中章节随机抽 2-3 段
        String styleSamples = buildStyleSamples(selectedChapters);

        // ⑤ 人物关系摘要
        String entitySummary = buildEntitySummary(novelId, retrievalContext);

        // ⑥ 计算起始章节号
        int startChapterNum = selectedChapters.stream()
                .mapToInt(Chapter::getChapterNum)
                .max().orElse(0) + 1;

        // ⑦ 组装用户提示
        String userPrompt = buildUserPrompt(outline, anchor, retrievalContext, styleSamples, entitySummary,
                foreword, afterword, prompt, chapterCount, startChapterNum);

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        messages.add(Map.of("role", "user", "content", userPrompt));
        return messages;
    }

    // ═══════════════════════════════════════════════════════════════

    private List<Chapter> resolveChapters(UUID novelId, List<UUID> chapterIds) {
        if (chapterIds != null && !chapterIds.isEmpty()) {
            List<Chapter> chapters = chapterRepository.findAllById(chapterIds);
            // 验证所有章节都属于指定小说，防止跨小说数据泄露
            for (Chapter ch : chapters) {
                if (!ch.getNovel().getId().equals(novelId)) {
                    throw new RuntimeException("章节 " + ch.getChapterNum() + " 不属于该小说");
                }
            }
            return chapters;
        }
        return chapterRepository.findByNovelIdOrderByChapterNumAsc(novelId);
    }

    /** 取最近一章末尾 ~1500 字原文，不经检索直接注入 */
    private String buildAnchorText(List<Chapter> chapters) {
        if (chapters.isEmpty()) return "（无前文章节）";
        Chapter last = chapters.stream()
                .max(Comparator.comparingInt(Chapter::getChapterNum))
                .orElse(chapters.get(chapters.size() - 1));
        String content = last.getContent();
        if (content == null || content.isBlank()) return "（章节内容为空）";
        // 取末尾 ~1500 字
        int start = Math.max(0, content.length() - 1500);
        String tail = content.substring(start);
        return "第" + last.getChapterNum() + "章 " + last.getTitle() + " 末尾：\n\n" + tail;
    }

    /** 双路检索：用用户的 prompt + foreword + afterword 作为查询。embedding 服务挂了不阻塞续写。 */
    private String buildRetrievalContext(UUID novelId, List<UUID> chapterIds,
                                          String foreword, String afterword, String prompt) {
        String query = buildSearchQuery(foreword, afterword, prompt);
        if (query.isBlank()) return "";
        try {
            RetrievalResult result;
            if (chapterIds != null && !chapterIds.isEmpty()) {
                Set<UUID> seen = new LinkedHashSet<>();
                List<RetrievalService.ScoredChunk> allChunks = new ArrayList<>();
                for (UUID cid : chapterIds) {
                    RetrievalResult r = retrievalService.retrieve(novelId, cid, query);
                    for (var chunk : r.fused()) {
                        if (seen.add(chunk.id())) allChunks.add(chunk);
                    }
                }
                result = new RetrievalResult(allChunks, List.of(), List.of());
            } else {
                result = retrievalService.retrieve(novelId, query);
            }
            return result.toContextString();
        } catch (Exception e) {
            log.warn("检索服务暂时不可用（{}），跳过检索继续续写", e.getMessage());
            return "";
        }
    }

    /** 联网搜索：用 searchHint 或拼接 foreword+afterword+prompt 作为查询 */
    private String buildSearchContext(String foreword, String afterword, String prompt,
                                       List<String> searchSites, String searchHint) {
        if (searchSites == null || searchSites.isEmpty()) return null;
        try {
            String rawQuery = searchHint != null && !searchHint.isBlank()
                    ? searchHint.strip()
                    : (foreword + " " + afterword + " " + prompt).trim();
            if (rawQuery.isBlank()) return null;
            String searchQuery = webSearchService.optimizeQuery(rawQuery);
            var searchResults = webSearchService.search(searchQuery, searchSites);
            if (searchResults.isEmpty()) {
                log.info("Site-filtered search empty for continue, retrying without site filter");
                searchResults = webSearchService.search(searchQuery, null);
            }
            return webSearchService.toContextString(searchResults);
        } catch (Exception e) {
            log.warn("Web search failed for continue: {}", e.getMessage());
            return null;
        }
    }

    private String buildSearchQuery(String foreword, String afterword, String prompt) {
        List<String> parts = new ArrayList<>();
        if (foreword != null && !foreword.isBlank()) parts.add(foreword);
        if (afterword != null && !afterword.isBlank()) parts.add(afterword);
        if (prompt != null && !prompt.isBlank()) parts.add(prompt);
        return String.join(" ", parts);
    }

    /** 从选中章节随机抽 2-3 段原文作为风格样本 */
    private String buildStyleSamples(List<Chapter> chapters) {
        if (chapters.isEmpty()) return "";
        Random rand = new Random();
        List<String> allParagraphs = new ArrayList<>();
        for (Chapter ch : chapters) {
            if (ch.getContent() == null || ch.getContent().isBlank()) continue;
            String[] paras = ch.getContent().split("\\n{2,}");
            for (String p : paras) {
                String trimmed = p.trim();
                if (trimmed.length() > 50) allParagraphs.add(trimmed);
            }
        }
        if (allParagraphs.isEmpty()) return "";
        Collections.shuffle(allParagraphs, rand);
        int count = Math.min(3, allParagraphs.size());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append("【样本").append(i + 1).append("】\n");
            sb.append(allParagraphs.get(i)).append("\n\n");
        }
        return sb.toString();
    }

    /** 人物关系摘要 */
    /**
     * 构建人物关系上下文：从检索到的 chunk 中提取出现的人物，
     * 只展示这些人物之间的关系，避免全量实体列表淹没 LLM。
     */
    private String buildEntitySummary(UUID novelId, String retrievalContext) {
        if (retrievalContext == null || retrievalContext.isBlank()) return "";
        List<GraphEntity> allEntities = graphEntityRepo.findByNovelId(novelId);
        if (allEntities.isEmpty()) return "";

        // 找出检索上下文里出现过的实体
        Set<String> contextNames = new LinkedHashSet<>();
        Map<String, GraphEntity> nameToEntity = new LinkedHashMap<>();
        for (GraphEntity e : allEntities) {
            // 检查实体名或别名是否在检索上下文里出现
            boolean appears = retrievalContext.contains(e.getName());
            if (!appears) {
                for (String alias : e.getAliases()) {
                    if (retrievalContext.contains(alias)) { appears = true; break; }
                }
            }
            if (appears) {
                contextNames.add(e.getName());
                nameToEntity.put(e.getName(), e);
            }
        }
        if (contextNames.isEmpty()) return "";

        // 查询这些实体间的关系
        Set<UUID> entityIds = allEntities.stream()
                .filter(e -> contextNames.contains(e.getName()))
                .map(GraphEntity::getId).collect(Collectors.toSet());
        List<GraphRelationship> relations = new ArrayList<>();
        for (UUID eid : entityIds) {
            relations.addAll(graphRelationRepo.findBySourceIdOrTargetId(eid, eid));
        }

        // 只保留两端都在上下文实体中的关系
        List<GraphRelationship> relevant = relations.stream()
                .filter(r -> contextNames.contains(r.getSource().getName())
                        && contextNames.contains(r.getTarget().getName()))
                .distinct()
                .toList();

        // 生成摘要
        StringBuilder sb = new StringBuilder();
        if (!relevant.isEmpty()) {
            sb.append("【参考片段中出现的人物关系】\n");
            for (GraphRelationship rel : relevant) {
                sb.append("- ").append(rel.getSource().getName())
                        .append(" —").append(relationLabel(rel.getRelationType())).append("→ ")
                        .append(rel.getTarget().getName());
                if (rel.getDescription() != null && !rel.getDescription().isBlank()) {
                    sb.append("（").append(rel.getDescription()).append("）");
                }
                sb.append("\n");
            }
        }

        // 补充出现但没关系的实体简述
        Set<String> relatedNames = relevant.stream()
                .flatMap(r -> Stream.of(r.getSource().getName(), r.getTarget().getName()))
                .collect(Collectors.toSet());
        List<GraphEntity> solo = allEntities.stream()
                .filter(e -> contextNames.contains(e.getName()) && !relatedNames.contains(e.getName()))
                .limit(8)
                .toList();
        if (!solo.isEmpty()) {
            sb.append("\n【其他出现的人物】\n");
            for (GraphEntity e : solo) {
                sb.append("- ").append(e.getName()).append("（").append(typeLabel(e.getType())).append("）");
                if (e.getDescription() != null && !e.getDescription().isBlank()) {
                    sb.append(": ").append(e.getDescription());
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /** Fallback：无检索上下文时用（resume 场景） */
    private String buildEntitySummary(UUID novelId) {
        return buildEntitySummary(novelId, "");
    }

    private String relationLabel(String type) {
        return switch (type) {
            case "FRIEND_OF", "ALLY", "结义兄弟" -> "结义/盟友";
            case "ENEMY_OF", "仇敌" -> "仇敌";
            case "MENTOR_OF", "师徒" -> "师徒";
            case "FAMILY", "亲属" -> "亲属";
            case "SERVANT_OF", "主仆" -> "主仆";
            case "KNOWS", "认识" -> "相识";
            default -> type;
        };
    }

    private String typeLabel(String type) {
        return switch (type) {
            case "PERSON" -> "人物";
            case "LOCATION" -> "地点";
            case "ITEM" -> "物品";
            case "CONCEPT" -> "概念";
            default -> type;
        };
    }

    /** 拼装最终的用户消息 */
    private String buildUserPrompt(String outline, String anchor, String retrievalContext,
                                    String styleSamples, String entitySummary,
                                    String foreword, String afterword, String prompt,
                                    int chapterCount, int startChapterNum) {
        StringBuilder sb = new StringBuilder();
        appendSection(sb, "剧情大纲", outline);
        appendSection(sb, "前文锚定 — 请严格从以下内容接着写", anchor);
        appendSection(sb, "参考片段", retrievalContext);
        appendSection(sb, "风格参考", styleSamples);
        appendSection(sb, "人物关系", entitySummary);

        sb.append("【续写要求】\n");
        sb.append("需要续写 ").append(chapterCount).append(" 章，");
        sb.append("从第 ").append(startChapterNum).append(" 章开始");
        if (chapterCount > 1) {
            sb.append("到第 ").append(startChapterNum + chapterCount - 1).append(" 章");
        }
        sb.append("。\n");

        if (foreword != null && !foreword.isBlank()) {
            sb.append("前言（前置背景）：").append(foreword).append("\n");
        }
        if (afterword != null && !afterword.isBlank()) {
            sb.append("后语（续写方向）：").append(afterword).append("\n");
        }
        if (prompt != null && !prompt.isBlank()) {
            sb.append("额外指令：").append(prompt).append("\n");
        }
        if (chapterCount > 1) {
            sb.append("\n请连续写出所有").append(chapterCount).append("章。");
            sb.append("每一章都必须以\"第X章 章节标题\"开头（如第").append(startChapterNum).append("章 XXX、第").append(startChapterNum + 1).append("章 XXX...），");
            sb.append("章节编号必须连续，标题不可省略。");
            sb.append("章与章之间用空行隔开。\n");
        }
        return sb.toString();
    }

    private void appendSection(StringBuilder sb, String title, String content) {
        if (content == null || content.isBlank()) return;
        sb.append("【").append(title).append("】\n");
        sb.append(content.strip()).append("\n\n");
    }

    /** 批量续写时的用户提示（单批 1-2 章），复用 buildUserPrompt 逻辑 */
    private String buildBatchUserPrompt(String outline, String anchor, String retrievalContext,
                                         String styleSamples, String entitySummary,
                                         String foreword, String afterword, String prompt,
                                         int batchChapters, int batchStart, String searchContext) {
        StringBuilder sb = new StringBuilder();
        appendSection(sb, "剧情大纲", outline);
        appendSection(sb, "前文锚定 — 请严格从以下内容接着写", anchor);
        appendSection(sb, "参考片段", retrievalContext);
        appendSection(sb, "风格参考", styleSamples);
        appendSection(sb, "人物关系", entitySummary);
        appendSection(sb, "联网参考 — 以下为实时搜索的外部资料，可借鉴其中的流行趋势、写作技巧，但不要求强制遵循", searchContext);

        sb.append("【续写要求】\n");
        sb.append("需要续写 ").append(batchChapters).append(" 章，");
        sb.append("从第 ").append(batchStart).append(" 章开始");
        if (batchChapters > 1) {
            sb.append("到第 ").append(batchStart + batchChapters - 1).append(" 章");
        }
        sb.append("。\n");

        if (foreword != null && !foreword.isBlank()) {
            sb.append("前言（前置背景）：").append(foreword).append("\n");
        }
        if (afterword != null && !afterword.isBlank()) {
            sb.append("后语（续写方向）：").append(afterword).append("\n");
        }
        if (prompt != null && !prompt.isBlank()) {
            sb.append("额外指令：").append(prompt).append("\n");
        }
        if (batchChapters > 1) {
            sb.append("\n请连续写出所有").append(batchChapters).append("章。");
            sb.append("每一章都必须以\"第X章 章节标题\"开头，");
            sb.append("章节编号必须连续，标题不可省略。");
            sb.append("章与章之间用空行隔开。\n");
        }
        return sb.toString();
    }

    /** 取文本末尾 ~n 字作为下一批的前文锚定 */
    private String extractTail(String text, int chars) {
        if (text == null || text.isBlank()) return "（无前文）";
        int start = Math.max(0, text.length() - chars);
        return text.substring(start);
    }

    // ═══ 续写作业记录 ═══

    /** 创建 GENERATING 状态的作业，返回 jobId */
    @Transactional
    public UUID createGeneratingJob(UUID novelId, UUID userId, List<UUID> chapterIds,
                                     String foreword, String afterword, String prompt,
                                     int chapterCount, List<String> searchSites, String searchHint) {
        Novel novel = novelRepository.findById(novelId).orElse(null);
        User user = userRepository.findById(userId).orElse(null);
        if (novel == null || user == null) return null;
        String chIds = chapterIds != null && !chapterIds.isEmpty()
                ? chapterIds.stream().map(UUID::toString).collect(Collectors.joining(","))
                : null;
        String chTitles = null;
        if (chapterIds != null && !chapterIds.isEmpty()) {
            chTitles = chapterRepository.findAllById(chapterIds).stream()
                    .map(ch -> "第" + ch.getChapterNum() + "章 " + ch.getTitle())
                    .collect(Collectors.joining(","));
        }
        String searchSitesStr = searchSites != null && !searchSites.isEmpty()
                ? String.join(",", searchSites) : null;
        ContinueJob job = ContinueJob.builder()
                .user(user).novel(novel).novelTitle(novel.getTitle())
                .chapterIds(chIds).chapterTitles(chTitles)
                .chapterCount(chapterCount)
                .foreword(foreword).afterword(afterword).prompt(prompt)
                .searchSites(searchSitesStr).searchHint(searchHint)
                .resultLength(0).status("GENERATING")
                .build();
        job = continueJobRepo.save(job);
        log.info("ContinueJob created (GENERATING): id={}", job.getId());
        return job.getId();
    }

    /** 生成完成：写入结果，状态改为 COMPLETED */
    @Transactional
    public void completeJob(UUID jobId, String result) {
        continueJobRepo.findById(jobId).ifPresent(job -> {
            job.setResult(result);
            job.setResultLength(result != null ? result.length() : 0);
            job.setStatus("COMPLETED");
            continueJobRepo.save(job);
            log.info("ContinueJob completed: id={} chars={}", jobId, job.getResultLength());
        });
    }

    /** 生成失败 */
    @Transactional
    public void failJob(UUID jobId) {
        continueJobRepo.findById(jobId).ifPresent(job -> {
            job.setStatus("FAILED");
            continueJobRepo.save(job);
            log.info("ContinueJob failed: id={}", jobId);
        });
    }

    /** 分页列举作业记录 */
    @Transactional(readOnly = true)
    public Map<String, Object> listJobs(UUID userId, int page, int size) {
        var pageResult = continueJobRepo.findByUserId(userId,
                org.springframework.data.domain.PageRequest.of(page, size,
                        org.springframework.data.domain.Sort.by("createdAt").descending()));
        List<Map<String, Object>> items = pageResult.getContent().stream()
                .map(j -> toJobMap(j, false))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", pageResult.getTotalElements());
        result.put("page", page);
        result.put("hasMore", pageResult.hasNext());
        return result;
    }

    /** 检查是否有进行中或暂停的任务 */
    @Transactional(readOnly = true)
    public Map<String, Object> getActiveJob(UUID novelId, UUID userId) {
        var job = continueJobRepo.findTopByNovelIdAndUserIdAndStatusInOrderByCreatedAtDesc(
                novelId, userId, List.of("GENERATING", "PARTIAL", "PENDING"));
        return job.map(j -> toJobMap(j, true)).orElse(null);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getJob(UUID jobId, UUID userId) {
        ContinueJob j = continueJobRepo.findById(jobId)
                .orElseThrow(() -> new RuntimeException("作业记录不存在"));
        if (!j.getUser().getId().equals(userId)) {
            throw new RuntimeException("无权访问");
        }
        return toJobMap(j, true);
    }

    /** 统计结果中有几个完整章节 */
    private int countChapters(String text) {
        if (text == null || text.isBlank()) return 0;
        int count = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^\\s*(?:#+\\s*)?第\\d+章\\s+").matcher(text);
        while (m.find()) count++;
        return count;
    }

    private Map<String, Object> toJobMap(ContinueJob j, boolean includeResult) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", j.getId().toString());
        m.put("novelId", j.getNovel().getId().toString());
        m.put("novelTitle", j.getNovelTitle());
        m.put("chapterIds", j.getChapterIds());
        m.put("chapterTitles", j.getChapterTitles());
        m.put("chapterCount", j.getChapterCount());
        m.put("foreword", j.getForeword());
        m.put("afterword", j.getAfterword());
        m.put("prompt", j.getPrompt());
        m.put("searchSites", j.getSearchSites());
        m.put("searchHint", j.getSearchHint());
        m.put("status", j.getStatus());
        m.put("resultLength", j.getResultLength());
        m.put("createdAt", j.getCreatedAt());
        if (includeResult) {
            m.put("result", j.getResult());
        } else {
            m.put("resultPreview", j.getResult() != null && j.getResult().length() > 200
                    ? j.getResult().substring(0, 200) + "..."
                    : j.getResult());
        }
        return m;
    }
}

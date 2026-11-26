package com.novelagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.entity.*;
import com.novelagent.repository.*;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * 剧情事件抽取：LLM 逐章提取关键剧情节点，构成剧情大纲图。
 * 全量重建 + 增量更新，异步执行。
 */
@Slf4j
@Service
public class PlotBuildService {

    private final NovelRepository novelRepository;
    private final ChapterRepository chapterRepository;
    private final PlotEventRepository plotEventRepo;
    private final LlmService llmService;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public PlotBuildService(NovelRepository novelRepository, ChapterRepository chapterRepository,
                            PlotEventRepository plotEventRepo, LlmService llmService,
                            EntityManager entityManager, TransactionTemplate transactionTemplate) {
        this.novelRepository = novelRepository;
        this.chapterRepository = chapterRepository;
        this.plotEventRepo = plotEventRepo;
        this.llmService = llmService;
        this.entityManager = entityManager;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = new ObjectMapper();
    }

    @org.springframework.scheduling.annotation.Async("streamExecutor")
    public void buildPlotAsync(UUID novelId, UUID userId) {
        log.info("=== buildPlotAsync START ===");
        try {
            transactionTemplate.executeWithoutResult(status -> buildPlotInTx(novelId, userId));
            log.info("=== buildPlotAsync DONE ===");
        } catch (Exception e) {
            log.error("Plot build failed", e);
        }
    }

    private void buildPlotInTx(UUID novelId, UUID userId) {
        novelRepository.findByIdAndUserId(novelId, userId)
                .orElseThrow(() -> new RuntimeException("小说不存在或无权操作"));

        plotEventRepo.deleteByNovelId(novelId);
        entityManager.flush();

        List<Chapter> chapters = chapterRepository.findByNovelIdOrderByChapterNumAsc(novelId);
        log.info("=== 剧情抽取: {} chapters ===", chapters.size());

        // Phase 1: 逐章抽事件（内存，不存库）
        List<String> eventSummaries = new ArrayList<>();
        for (Chapter ch : chapters) {
            List<PlotEvent> events = extractPlot(ch, 0);
            for (PlotEvent e : events) eventSummaries.add(e.getSummary());
            log.info("  Ch{}: {} events extracted", ch.getChapterNum(), events.size());
        }
        log.info("Phase 1 done: {} events (in-memory)", eventSummaries.size());

        // ── Phase 2: 合并为宏观弧线（只存弧线）──
        List<PlotArc> arcs = extractArcs(eventSummaries);
        Novel novel = novelRepository.findById(novelId).orElseThrow();
        for (int i = 0; i < arcs.size(); i++) {
            PlotArc arc = arcs.get(i);
            plotEventRepo.save(PlotEvent.builder()
                    .novel(novel)
                    .eventIndex(i)
                    .summary(arc.summary)
                    .keyEntities(arc.keyEntities)
                    .eventType("ARC")
                    .build());
        }
        log.info("Phase 2 done: {} macro arcs saved", arcs.size());
        log.info("=== 剧情抽取完成: {} arcs ===", arcs.size());
    }

    // ─── Phase 2: 合并事件为宏观弧线 ──────────────────────────

    private List<PlotArc> extractArcs(List<String> eventSummaries) {
        if (eventSummaries.isEmpty()) return List.of();

        String eventsText = eventSummaries.stream()
                .map(s -> "- " + s)
                .reduce("", (a, b) -> a + b + "\n");

        String prompt = String.format("""
                你是小说剧情结构分析师。以下是按章节顺序排列的剧情事件列表，请将它们归纳为宏观故事弧线（3-8个为宜）。

                每个弧线需要:
                  summary: 该弧线的核心内容概括（2-3句话）
                  entities: 涉及的3-5个关键实体

                只返回 JSON 数组:
                [
                  {"summary":"波之国篇：第七班首次执行高级任务，对抗雾隐叛忍桃地再不斩与白，鸣人首次展现九尾查克拉","entities":["鸣人","佐助","卡卡西","再不斩","白"]},
                  {"summary":"中忍考试篇：木叶举办中忍考试，大蛇丸伪装潜入并杀死三代火影，木叶遭受重创","entities":["大蛇丸","三代火影","鸣人","佐助","我爱罗"]}
                ]

                --- 事件列表 ---
                %s""", eventsText);

        List<Map<String, String>> messages = List.of(Map.of("role", "user", "content", prompt));
        String reply = llmService.chat(messages, 4096);
        return parseArcResponse(reply);
    }

    private List<PlotArc> parseArcResponse(String reply) {
        try {
            String json = reply.trim();
            if (json.startsWith("```")) json = json.substring(json.indexOf("\n") + 1);
            if (json.endsWith("```")) json = json.substring(0, json.lastIndexOf("```")).trim();

            List<Map<String, Object>> list = objectMapper.readValue(json, new TypeReference<>() {});
            List<PlotArc> result = new ArrayList<>();
            for (Map<String, Object> m : list) {
                PlotArc arc = new PlotArc();
                arc.summary = (String) m.get("summary");
                arc.keyEntities = m.containsKey("entities")
                        ? (List<String>) m.get("entities") : List.of();
                result.add(arc);
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to parse arc JSON", e);
            return List.of();
        }
    }

    private static class PlotArc {
        String summary;
        List<String> keyEntities = List.of();
    }

    @org.springframework.scheduling.annotation.Async("streamExecutor")
    public void updatePlotForChapter(UUID novelId, UUID chapterId, UUID userId) {
        log.info("=== updatePlotForChapter: chapter={} ===", chapterId);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                novelRepository.findByIdAndUserId(novelId, userId)
                        .orElseThrow(() -> new RuntimeException("小说不存在或无权操作"));
                Chapter chapter = chapterRepository.findById(chapterId)
                        .orElseThrow(() -> new RuntimeException("章节不存在"));
                Novel novel = novelRepository.findById(novelId).orElseThrow();

                // 删旧事件
                plotEventRepo.deleteByChapterId(chapterId);
                entityManager.flush();

                // 确定 event_index 起点
                long count = plotEventRepo.countByNovelId(novelId);
                int globalIndex = (int) count;

                List<PlotEvent> events = extractPlot(chapter, globalIndex);
                for (PlotEvent e : events) {
                    e.setEventIndex(globalIndex++);
                    plotEventRepo.save(e);
                }
                log.info("=== 增量剧情完成: {} events ===", events.size());
            });
        } catch (Exception e) {
            log.error("Incremental plot update failed for chapter {}", chapterId, e);
        }
    }

    private List<PlotEvent> extractPlot(Chapter chapter, int startIndex) {
        String prompt = String.format("""
                你是小说剧情分析专家。请从以下章节中提取最重要的剧情转折点，只抓影响主线走向的大事件，不要细节。每章最多3个。

                只返回 JSON 数组（不超过3条）:
                [
                  {"summary":"第七班结成，鸣人佐助小樱组成三人小队","type":"PLOT","entities":["鸣人","佐助","小樱","卡卡西"]},
                  {"summary":"中忍考试大蛇丸入侵木叶，三代火影战死","type":"CLIMAX","entities":["大蛇丸","三代火影","木叶"]}
                ]

                type: PLOT(剧情推动) / CLIMAX(高潮) / REVELATION(重大揭秘) / RESOLUTION(阶段收尾)
                如果本章没有重大转折点，返回空数组 []。

                --- 章节内容 ---
                %s""", truncate(chapter.getContent(), 5000));

        List<Map<String, String>> messages = List.of(Map.of("role", "user", "content", prompt));
        String reply = llmService.chat(messages, 4096);
        return parsePlotResponse(reply, chapter, startIndex);
    }

    private List<PlotEvent> parsePlotResponse(String reply, Chapter chapter, int startIndex) {
        try {
            String json = reply.trim();
            if (json.startsWith("```")) json = json.substring(json.indexOf("\n") + 1);
            if (json.endsWith("```")) json = json.substring(0, json.lastIndexOf("```")).trim();

            List<Map<String, Object>> list = objectMapper.readValue(json, new TypeReference<>() {});
            List<PlotEvent> result = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                Map<String, Object> m = list.get(i);
                PlotEvent e = PlotEvent.builder()
                        .novel(chapter.getNovel())
                        .chapter(chapter)
                        .eventIndex(startIndex + i)
                        .summary((String) m.get("summary"))
                        .eventType((String) m.getOrDefault("type", "PLOT"))
                        .keyEntities(m.containsKey("entities")
                                ? (List<String>) m.get("entities") : List.of())
                        .build();
                result.add(e);
            }
            return result;
        } catch (Exception ex) {
            log.error("Failed to parse plot JSON (len={}): {}", reply.length(),
                    reply.substring(0, Math.min(300, reply.length())));
            return List.of();
        }
    }

    private String truncate(String text, int maxLen) {
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen / 2) + "\n...（中间省略）...\n" + text.substring(text.length() - maxLen / 2);
    }

    // ─── 导出 ────────────────────────────────────────────────

    public List<Map<String, Object>> getPlot(UUID novelId, UUID userId) {
        novelRepository.findByIdAndUserId(novelId, userId)
                .orElseThrow(() -> new RuntimeException("小说不存在或无权操作"));

        List<PlotEvent> events = plotEventRepo.findByNovelIdOrderByEventIndexAsc(novelId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (PlotEvent e : events) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId().toString());
            m.put("eventIndex", e.getEventIndex());
            m.put("summary", e.getSummary());
            m.put("eventType", e.getEventType());
            m.put("keyEntities", e.getKeyEntities());
            m.put("chapterId", e.getChapter() != null ? e.getChapter().getId().toString() : null);
            m.put("chapterNum", e.getChapter() != null ? e.getChapter().getChapterNum() : null);
            result.add(m);
        }
        return result;
    }

    /** 生成可读的剧情大纲文本，注入检索上下文 */
    public String toOutlineText(UUID novelId) {
        List<PlotEvent> events = plotEventRepo.findByNovelIdOrderByEventIndexAsc(novelId);
        if (events.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("小说剧情大纲：\n");
        for (PlotEvent e : events) {
            sb.append("## ").append(e.getSummary()).append("\n");
        }
        return sb.toString();
    }
}

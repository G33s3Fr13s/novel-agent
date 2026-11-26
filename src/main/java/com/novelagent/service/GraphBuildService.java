package com.novelagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.entity.*;
import com.novelagent.repository.*;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 图谱构建：LLM 抽取人物/地点/物品/概念 + 关系 → 图存储。
 *
 * 策略：每章一次 LLM 调用同时抽取实体+关系（非分页），
 * 跨章合并去重后批量 embedding，大幅减少 API 调用次数。
 */
@Slf4j
@Service
public class GraphBuildService {

    /** 同一小说只允许一个图谱构建任务（增量 or 全量）并发执行 */
    private final ConcurrentHashMap<UUID, Object> buildLocks = new ConcurrentHashMap<>();

    private final NovelRepository novelRepository;
    private final ChapterRepository chapterRepository;
    private final GraphEntityRepository entityRepo;
    private final GraphRelationshipRepository relationRepo;
    private final LlmService llmService;
    private final EmbeddingService embeddingService;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public GraphBuildService(NovelRepository novelRepository, ChapterRepository chapterRepository,
                             GraphEntityRepository entityRepo, GraphRelationshipRepository relationRepo,
                             LlmService llmService, EmbeddingService embeddingService,
                             JdbcTemplate jdbcTemplate, EntityManager entityManager,
                             TransactionTemplate transactionTemplate) {
        this.novelRepository = novelRepository;
        this.chapterRepository = chapterRepository;
        this.entityRepo = entityRepo;
        this.relationRepo = relationRepo;
        this.llmService = llmService;
        this.embeddingService = embeddingService;
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 异步构建图谱。立即返回，后台线程执行。
     */
    @org.springframework.scheduling.annotation.Async("streamExecutor")
    public void buildGraphAsync(UUID novelId, UUID userId) {
        synchronized (buildLocks.computeIfAbsent(novelId, k -> new Object())) {
            try {
                log.info("=== buildGraphAsync START ===");
                transactionTemplate.executeWithoutResult(status -> buildGraphInTx(novelId, userId));
                log.info("=== buildGraphAsync DONE ===");
            } catch (Exception e) {
                log.error("Graph build failed", e);
            } finally {
                buildLocks.remove(novelId);
            }
        }
    }

    /**
     * 增量更新：只处理新上传的一章，不重建整本小说。
     * 上传章节后自动调用。
     */
    @org.springframework.scheduling.annotation.Async("streamExecutor")
    public void updateGraphForChapter(UUID novelId, UUID chapterId, UUID userId) {
        synchronized (buildLocks.computeIfAbsent(novelId, k -> new Object())) {
            try {
                log.info("=== updateGraphForChapter: chapter={} ===", chapterId);
                transactionTemplate.executeWithoutResult(status -> {
                // 验证小说归属，防止跨用户数据篡改
                novelRepository.findByIdAndUserId(novelId, userId)
                        .orElseThrow(() -> new RuntimeException("小说不存在或无权操作"));

                Chapter chapter = chapterRepository.findById(chapterId)
                        .orElseThrow(() -> new RuntimeException("章节不存在"));
                Novel novel = novelRepository.findById(novelId).orElseThrow();

                // 1. 抽取新章的实体 + 关系
                ChapterResult result = extractChapter(chapter);
                log.info("  Extracted: {} entities, {} relations", result.entities.size(), result.relations.size());

                // 2. 合并实体：检查哪些已存在，哪些是新的
                List<GraphEntity> existingEntities = entityRepo.findByNovelId(novelId);
                Map<String, GraphEntity> existingMap = new LinkedHashMap<>();
                for (GraphEntity e : existingEntities) {
                    existingMap.put(e.getName(), e);
                }

                Map<String, GraphEntity> allEntities = new LinkedHashMap<>(existingMap);
                List<GraphEntity> newEntities = new ArrayList<>();

                for (RawEntity re : result.entities) {
                    if (allEntities.containsKey(re.name)) {
                        // 已有的实体：合并别名
                        GraphEntity existing = allEntities.get(re.name);
                        Set<String> aliases = new LinkedHashSet<>(existing.getAliases());
                        aliases.addAll(re.aliases);
                        existing.setAliases(new ArrayList<>(aliases));
                        if (existing.getDescription() == null || existing.getDescription().isBlank()) {
                            existing.setDescription(re.description);
                        }
                        entityRepo.save(existing);
                    } else {
                        // 新实体
                        GraphEntity ge = entityRepo.saveAndFlush(GraphEntity.builder()
                                .novel(novel).name(re.name).type(re.type)
                                .aliases(re.aliases).description(re.description)
                                .build());
                        allEntities.put(re.name, ge);
                        newEntities.add(ge);
                    }
                }
                entityManager.flush();

                // 3. 只为新实体做 embedding
                if (!newEntities.isEmpty()) {
                    List<String> embTexts = new ArrayList<>();
                    for (GraphEntity ge : newEntities) {
                        embTexts.add(ge.getName() + ": " + (ge.getDescription() != null ? ge.getDescription() : ""));
                    }
                    float[][] embeddings = embeddingService.embedBatch(embTexts);
                    for (int i = 0; i < newEntities.size(); i++) {
                        saveEntityEmbedding(newEntities.get(i).getId(), embeddings[i]);
                    }
                }

                // 4. 保存新章的关系（去重）
                int newRelCount = 0;
                for (RawRelation rr : result.relations) {
                    GraphEntity source = allEntities.get(rr.source);
                    GraphEntity target = allEntities.get(rr.target);
                    if (source == null || target == null) continue;

                    // 检查是否已存在
                    List<Integer> rows = jdbcTemplate.queryForList(
                            "SELECT 1 FROM graph_relationships WHERE source_entity_id=? AND target_entity_id=? AND relation_type=?",
                            Integer.class, source.getId(), target.getId(), rr.type);
                    if (rows.isEmpty()) {
                        relationRepo.save(GraphRelationship.builder()
                                .source(source).target(target)
                                .relationType(rr.type).description(rr.description)
                                .weight(rr.weight).build());
                        newRelCount++;
                    }
                }

                log.info("=== 增量图谱更新完成: {} new entities, {} new relations ===",
                        newEntities.size(), newRelCount);
            });
        } catch (Exception e) {
            log.error("Incremental graph update failed for chapter {}", chapterId, e);
        } finally {
            buildLocks.remove(novelId);
        }
        }
    }

    /** 在同一事务中执行图谱构建的完整流程 */
    private void buildGraphInTx(UUID novelId, UUID userId) {
        novelRepository.findByIdAndUserId(novelId, userId)
                .orElseThrow(() -> new RuntimeException("小说不存在或无权操作"));

        // 清空旧图谱
        List<GraphEntity> oldEntities = entityRepo.findByNovelId(novelId);
        for (var e : oldEntities) {
            relationRepo.deleteBySourceIdOrTargetId(e.getId(), e.getId());
        }
        entityRepo.deleteByNovelId(novelId);
        entityManager.flush();

        List<Chapter> chapters = chapterRepository.findByNovelIdOrderByChapterNumAsc(novelId);
        log.info("=== 图谱构建: {} chapters, 每章 1 次 LLM 调用 ===", chapters.size());

        // ── Phase 1: 逐章抽取实体 + 关系（一次 LLM 调用）──
        Map<String, RawEntity> mergedEntities = new LinkedHashMap<>();
        List<RawRelation> allRelations = new ArrayList<>();

        for (Chapter ch : chapters) {
            ChapterResult result = extractChapter(ch);
            // 合并实体
            for (RawEntity e : result.entities) {
                String key = e.name;
                if (mergedEntities.containsKey(key)) {
                    RawEntity existing = mergedEntities.get(key);
                    Set<String> aliases = new LinkedHashSet<>(existing.aliases);
                    aliases.addAll(e.aliases);
                    existing.aliases = new ArrayList<>(aliases);
                    if (existing.description == null || existing.description.isBlank()) {
                        existing.description = e.description;
                    }
                } else {
                    mergedEntities.put(key, e);
                }
            }
            allRelations.addAll(result.relations);
            log.info("  Ch{}: {} entities + {} relations",
                    ch.getChapterNum(), result.entities.size(), result.relations.size());
        }

        log.info("Phase 1 done: {} unique entities, {} raw relations",
                mergedEntities.size(), allRelations.size());

        // ── Phase 2: 存实体 + 批量 embedding ──
        Novel novel = novelRepository.findById(novelId).orElseThrow();
        Map<String, GraphEntity> savedEntities = new LinkedHashMap<>();

        // 先批量保存实体（拿到 ID）
        List<RawEntity> entityList = new ArrayList<>(mergedEntities.values());
        for (RawEntity re : entityList) {
            GraphEntity ge = entityRepo.saveAndFlush(GraphEntity.builder()
                    .novel(novel).name(re.name).type(re.type)
                    .aliases(re.aliases).description(re.description)
                    .build());
            savedEntities.put(re.name, ge);
        }

        // 批量 embedding（减少 API 调用）
        List<String> embTexts = new ArrayList<>();
        List<UUID> embIds = new ArrayList<>();
        for (RawEntity re : entityList) {
            String text = re.name + ": " + (re.description != null ? re.description : "");
            embTexts.add(text);
            embIds.add(savedEntities.get(re.name).getId());
        }
        float[][] embeddings = embeddingService.embedBatch(embTexts);
        for (int i = 0; i < embIds.size(); i++) {
            saveEntityEmbedding(embIds.get(i), embeddings[i]);
        }

        log.info("Phase 2 done: {} entities saved + embedded in {} batches",
                savedEntities.size(), (int) Math.ceil(embTexts.size() / 10.0));

        // ── Phase 3: 保存关系（去重） ──
        Map<String, GraphRelationship> relationMap = new LinkedHashMap<>();
        for (RawRelation rr : allRelations) {
            GraphEntity source = savedEntities.get(rr.source);
            GraphEntity target = savedEntities.get(rr.target);
            if (source == null || target == null) continue;

            String key = source.getId() + "|" + target.getId() + "|" + rr.type;
            if (relationMap.containsKey(key)) {
                if (rr.weight > relationMap.get(key).getWeight()) {
                    relationMap.get(key).setWeight(rr.weight);
                }
            } else {
                relationMap.put(key, GraphRelationship.builder()
                        .source(source).target(target)
                        .relationType(rr.type).description(rr.description)
                        .weight(rr.weight).build());
            }
        }
        for (GraphRelationship rel : relationMap.values()) {
            relationRepo.save(rel);
        }

        log.info("=== 图谱构建完成: {} entities, {} relations ===",
                savedEntities.size(), relationMap.size());
    }

    // ─── 一次 LLM 调用同时抽取实体和关系 ──────────────────────

    private ChapterResult extractChapter(Chapter ch) {
        String prompt = String.format("""
                你是小说分析专家。请一次完成两项任务：
                1. 提取章节中所有重要的人物(PERSON)、地点(LOCATION)、物品(ITEM)、功法概念(CONCEPT)
                2. 提取实体之间的关系

                返回一个 JSON 对象（不要 markdown 代码块）:
                {
                  "entities": [
                    {"name":"林夜","type":"PERSON","aliases":["叶林"],"description":"穿越者"},
                    {"name":"墨鸦","type":"PERSON","aliases":["冷煞宗副宗主"],"description":"金丹境强者"}
                  ],
                  "relations": [
                    {"source":"林夜","target":"墨鸦","type":"敌对","description":"杀父仇人","weight":0.9}
                  ]
                }

                relations 中 source/target 必须是 entities 里出现的 name。
                type 可选: 师徒/敌对/朋友/拥有/位于/属于/修炼/创造/拯救/追杀/认识/掌控

                --- 章节内容 ---
                %s""", truncate(ch.getContent(), 5000));

        List<Map<String, String>> messages = List.of(
                Map.of("role", "user", "content", prompt)
        );
        String reply = chatWithRetry(messages, 3);
        return parseChapterResult(reply);
    }

    /** 带重试的 LLM 调用，应对 Connection reset。图谱提取需要长输出，用 16384 max_tokens */
    private String chatWithRetry(List<Map<String, String>> messages, int maxRetries) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return llmService.chat(messages, 16384);
            } catch (Exception e) {
                if (attempt == maxRetries) throw e;
                long wait = attempt * 2000L;
                log.warn("LLM call failed (attempt {}/{}), retrying in {}s: {}",
                        attempt, maxRetries, wait / 1000, e.getMessage());
                try { Thread.sleep(wait); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
        throw new RuntimeException("Unreachable");
    }

    private ChapterResult parseChapterResult(String reply) {
        try {
            String json = reply.trim();
            if (json.startsWith("```")) json = json.substring(json.indexOf("\n") + 1);
            if (json.endsWith("```")) json = json.substring(0, json.lastIndexOf("```")).trim();

            Map<String, Object> root = objectMapper.readValue(json, new TypeReference<>() {});

            ChapterResult result = new ChapterResult();
            result.entities = new ArrayList<>();
            result.relations = new ArrayList<>();

            List<Map<String, Object>> entities = (List<Map<String, Object>>) root.get("entities");
            if (entities != null) {
                for (Map<String, Object> m : entities) {
                    RawEntity e = new RawEntity();
                    e.name = (String) m.get("name");
                    e.type = (String) m.get("type");
                    e.aliases = m.containsKey("aliases") ? (List<String>) m.get("aliases") : List.of();
                    e.description = (String) m.get("description");
                    result.entities.add(e);
                }
            }

            List<Map<String, Object>> relations = (List<Map<String, Object>>) root.get("relations");
            if (relations != null) {
                for (Map<String, Object> m : relations) {
                    RawRelation r = new RawRelation();
                    r.source = (String) m.get("source");
                    r.target = (String) m.get("target");
                    r.type = (String) m.get("type");
                    r.description = (String) m.get("description");
                    r.weight = m.containsKey("weight") ? ((Number) m.get("weight")).floatValue() : 1.0f;
                    result.relations.add(r);
                }
            }

            return result;
        } catch (Exception e) {
            log.error("Failed to parse chapter result (len={}): {}", reply.length(),
                    reply.substring(0, Math.min(300, reply.length())));
            return new ChapterResult();
        }
    }

    // ─── 辅助 ────────────────────────────────────────────────

    private String truncate(String text, int maxLen) {
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen / 2) + "\n...（中间省略）...\n" + text.substring(text.length() - maxLen / 2);
    }

    private void saveEntityEmbedding(UUID entityId, float[] vec) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) { if (i > 0) sb.append(","); sb.append(vec[i]); }
        sb.append("]");
        jdbcTemplate.update("UPDATE graph_entities SET embedding = ?::vector WHERE id = ?", sb.toString(), entityId);
    }

    // ─── 内部数据类 ──────────────────────────────────────────

    private static class RawEntity {
        String name, type, description;
        List<String> aliases = List.of();
    }

    private static class RawRelation {
        String source, target, type, description;
        float weight = 1.0f;
    }

    private static class ChapterResult {
        List<RawEntity> entities = List.of();
        List<RawRelation> relations = List.of();
    }
}

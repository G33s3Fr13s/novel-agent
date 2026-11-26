package com.novelagent.service;

import com.novelagent.entity.Chunk;
import com.novelagent.entity.GraphEntity;
import com.novelagent.entity.GraphRelationship;
import com.novelagent.repository.ChunkRepository;
import com.novelagent.repository.GraphEntityRepository;
import com.novelagent.repository.GraphRelationshipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 双路检索：向量相似度 + 知识图谱 → RRF 融合排序。
 *
 * 向量路：问题 embedding → pgvector cosine 查 chunks
 * 图谱路：问题 embedding → 匹配实体 → 遍历关联实体 → 命中 chunks
 * RRF 融合：两路结果加权合并去重排序
 */
@Slf4j
@Service
@RequiredArgsConstructor
@org.springframework.transaction.annotation.Transactional(readOnly = true)
public class RetrievalService {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;
    private final ChunkRepository chunkRepository;
    private final GraphEntityRepository entityRepo;
    private final GraphRelationshipRepository relationRepo;

    private static final int VECTOR_TOP_K = 10;
    private static final int GRAPH_TOP_K = 10;
    private static final int FUSION_TOP_K = 8;

    @org.springframework.beans.factory.annotation.Value("${app.retrieval.graph-enabled:false}")
    private boolean graphEnabled;
    private static final int ENTITY_MATCH_TOP = 5;

    /**
     * 双路检索入口：返回融合后的 chunks + 诊断信息。
     */
    public RetrievalResult retrieve(UUID novelId, String question) {
        return retrieve(novelId, null, question);
    }

    /** 支持按章节过滤的检索 */
    public RetrievalResult retrieve(UUID novelId, UUID chapterId, String question) {
        float[] questionEmb = embeddingService.embed(question);

        // ── 向量检索 ──
        List<ScoredChunk> vectorResults = vectorSearch(novelId, chapterId, questionEmb);
        log.debug("Vector search: {} chunks", vectorResults.size());

        // ── 图谱检索（可通过 app.retrieval.graph-enabled 关闭）──
        List<ScoredChunk> graphResults;
        if (graphEnabled) {
            graphResults = graphSearch(novelId, chapterId, questionEmb, question);
            log.debug("Graph search: {} chunks", graphResults.size());
        } else {
            graphResults = List.of();
        }

        // ── RRF 融合 ──
        List<ScoredChunk> fused = rrfFuse(vectorResults, graphResults, FUSION_TOP_K);

        // 诊断
        Set<UUID> vectorIds = vectorResults.stream().map(ScoredChunk::id).collect(Collectors.toSet());
        Set<UUID> graphIds = graphResults.stream().map(ScoredChunk::id).collect(Collectors.toSet());
        long fromVector = fused.stream().filter(c -> vectorIds.contains(c.id)).count();
        long fromGraph = fused.stream().filter(c -> graphIds.contains(c.id)).count();
        long overlap = fused.stream().filter(c -> vectorIds.contains(c.id) && graphIds.contains(c.id)).count();
        log.info("Fused top-{}: vector贡献={}, graph贡献={}, 重叠={} (graphEnabled={})",
                FUSION_TOP_K, fromVector, fromGraph, overlap, graphEnabled);

        return new RetrievalResult(fused, vectorResults, graphResults);
    }

    // ═══════════════════════════════════════════════════════════════
    // 向量检索：pgvector cosine 相似度
    // ═══════════════════════════════════════════════════════════════

    private List<ScoredChunk> vectorSearch(UUID novelId, UUID chapterId, float[] embedding) {
        String vecStr = vectorToString(embedding);
        String chapterFilter = chapterId != null ? " AND c.chapter_id = ?::uuid" : "";
        String sql = """
                SELECT c.id, c.content, c.chunk_index,
                       1 - (c.embedding <=> ?::vector) AS similarity
                FROM chunks c
                WHERE c.novel_id = ? AND c.embedding IS NOT NULL
                """ + chapterFilter + """
                 ORDER BY c.embedding <=> ?::vector
                LIMIT ?
                """;

        return jdbcTemplate.query(sql,
                ps -> {
                    int idx = 1;
                    ps.setString(idx++, vecStr);
                    ps.setObject(idx++, novelId);
                    if (chapterId != null) ps.setObject(idx++, chapterId);
                    ps.setString(idx++, vecStr);
                    ps.setInt(idx++, VECTOR_TOP_K);
                },
                (rs, rowNum) -> new ScoredChunk(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("content"),
                        rs.getInt("chunk_index"),
                        rs.getDouble("similarity"),
                        "vector"
                ));
    }

    // ═══════════════════════════════════════════════════════════════
    // 图谱检索：embedding 匹配实体 → 遍历关系 → 命中 chunks
    // ═══════════════════════════════════════════════════════════════

    private List<ScoredChunk> graphSearch(UUID novelId, UUID chapterId, float[] questionEmb, String question) {
        // 1. 找到 top-K 匹配实体（需要实体的 embedding）
        List<GraphEntity> matchedEntities = findMatchingEntities(novelId, questionEmb, question);
        if (matchedEntities.isEmpty()) return List.of();
        log.debug("  Matched entities: {}", matchedEntities.stream().map(GraphEntity::getName).toList());

        // 2. 扩展到关联实体
        Set<String> entityNames = new LinkedHashSet<>();
        for (GraphEntity e : matchedEntities) {
            entityNames.add(e.getName());
            for (String alias : e.getAliases()) entityNames.add(alias);
        }

        // 查询关系，把关联实体也加进来
        Set<UUID> entityIds = matchedEntities.stream().map(GraphEntity::getId).collect(Collectors.toSet());
        List<GraphRelationship> relations = new ArrayList<>();
        for (UUID eid : entityIds) {
            relations.addAll(relationRepo.findBySourceIdOrTargetId(eid, eid));
        }
        for (GraphRelationship rel : relations) {
            GraphEntity neighbor = entityIds.contains(rel.getSource().getId()) ? rel.getTarget() : rel.getSource();
            entityNames.add(neighbor.getName());
            for (String alias : neighbor.getAliases()) entityNames.add(alias);
        }
        log.debug("  Entity names to search: {}", entityNames);

        // 3. 在 chunks 中找包含这些实体名的 chunk，用语义相似度设门槛
        List<Chunk> allChunks = chapterId != null
                ? chunkRepository.findByChapterIdOrderByChunkIndexAsc(chapterId)
                : chunkRepository.findByNovelIdOrderByChunkIndexAsc(novelId);
        List<UUID> candidates = new ArrayList<>();
        for (Chunk c : allChunks) {
            for (String name : entityNames) {
                if (c.getContent().contains(name)) {
                    candidates.add(c.getId());
                    break;
                }
            }
        }
        if (candidates.isEmpty()) return List.of();

        // 4. 图结构打分：不靠 embedding，纯靠实体命中 + 关系权重
        Set<String> directMatchNames = matchedEntities.stream()
                .flatMap(e -> {
                    Set<String> s = new LinkedHashSet<>();
                    s.add(e.getName());
                    s.addAll(e.getAliases());
                    return s.stream();
                }).collect(Collectors.toSet());

        Map<UUID, Chunk> chunkMap = new LinkedHashMap<>();
        for (Chunk c : allChunks) {
            if (candidates.contains(c.getId())) chunkMap.put(c.getId(), c);
        }

        List<ScoredChunk> scored = new ArrayList<>();
        for (UUID cid : candidates) {
            Chunk c = chunkMap.get(cid);
            if (c == null) continue;
            String content = c.getContent();
            double score = 0;

            // 实体名命中记分
            for (String name : entityNames) {
                if (!content.contains(name)) continue;
                score += directMatchNames.contains(name) ? 3 : 1;
            }

            // 同时包含一对有关系的两个实体 → 按关系权重加分
            for (GraphRelationship rel : relations) {
                String src = rel.getSource().getName();
                String tgt = rel.getTarget().getName();
                if (content.contains(src) && content.contains(tgt)) {
                    score += 2.0 * rel.getWeight();
                }
            }

            if (score > 0) {
                scored.add(new ScoredChunk(c.getId(), content, c.getChunkIndex(), score, "graph"));
            }
        }

        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return scored.stream().limit(GRAPH_TOP_K).toList();
    }

    private List<GraphEntity> findMatchingEntities(UUID novelId, float[] questionEmb, String question) {
        List<GraphEntity> allEntities = entityRepo.findByNovelId(novelId);
        if (allEntities.isEmpty()) return List.of();

        String vecStr = vectorToString(questionEmb);
        Set<UUID> matched = new LinkedHashSet<>();

        // 方式1：嵌入相似度 top-K
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, 1 - (embedding <=> ?::vector) AS similarity FROM graph_entities WHERE novel_id = ? AND embedding IS NOT NULL ORDER BY embedding <=> ?::vector LIMIT ?",
                vecStr, novelId, vecStr, ENTITY_MATCH_TOP);
        for (var row : rows) matched.add((UUID) row.get("id"));

        // 方式2：文本匹配 — 问题中直接出现实体名或别名，必中
        for (GraphEntity e : allEntities) {
            if (question.contains(e.getName())) { matched.add(e.getId()); continue; }
            for (String alias : e.getAliases()) {
                if (question.contains(alias)) { matched.add(e.getId()); break; }
            }
        }

        Map<UUID, Double> scores = new LinkedHashMap<>();
        for (var row : rows) {
            scores.put((UUID) row.get("id"), (Double) row.get("similarity"));
        }

        return allEntities.stream()
                .filter(e -> matched.contains(e.getId()))
                .sorted((a, b) -> {
                    double sa = scores.getOrDefault(a.getId(), 0.0);
                    double sb = scores.getOrDefault(b.getId(), 0.0);
                    return Double.compare(sb, sa);
                })
                .limit(ENTITY_MATCH_TOP + 5)
                .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════
    // RRF 融合
    // ═══════════════════════════════════════════════════════════════

    private List<ScoredChunk> rrfFuse(List<ScoredChunk> list1, List<ScoredChunk> list2, int topK) {
        Map<UUID, Double> rrfScores = new LinkedHashMap<>();
        Map<UUID, ScoredChunk> chunkMap = new LinkedHashMap<>();

        // RRF: score = sum(1 / (k + rank))  for each list
        // 图谱权重打折（k 越大贡献越小），避免噪音压过语义
        int k1 = 60;   // 向量路
        int k2 = 60;   // 图谱路（平等权重）
        addRrfScores(list1, rrfScores, k1);
        addRrfScores(list2, rrfScores, k2);
        list1.forEach(c -> chunkMap.putIfAbsent(c.id, c));
        list2.forEach(c -> chunkMap.putIfAbsent(c.id, c));

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> {
                    ScoredChunk c = chunkMap.get(e.getKey());
                    return new ScoredChunk(c.id, c.content, c.chunkIndex, e.getValue(), "rrf");
                })
                .collect(Collectors.toList());
    }

    private void addRrfScores(List<ScoredChunk> list, Map<UUID, Double> scores, int k) {
        for (int i = 0; i < list.size(); i++) {
            UUID id = list.get(i).id;
            double rrf = 1.0 / (k + i + 1);
            scores.merge(id, rrf, Double::sum);
        }
    }

    // ═══════════════════════════════════════════════════════════════

    private String vectorToString(float[] vec) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vec[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    // 结果类型
    // ═══════════════════════════════════════════════════════════════

    public record ScoredChunk(UUID id, String content, int chunkIndex,
                               double score, String source) {}

    public record RetrievalResult(
            List<ScoredChunk> fused,       // 最终结果
            List<ScoredChunk> vectorOnly,  // 纯向量结果（诊断用）
            List<ScoredChunk> graphOnly    // 纯图谱结果（诊断用）
    ) {
        public String toContextString() {
            if (fused.isEmpty()) return "";
            var sb = new StringBuilder();
            for (int i = 0; i < fused.size(); i++) {
                sb.append("【参考资料").append(i + 1).append("】\n");
                sb.append(fused.get(i).content).append("\n\n");
            }
            return sb.toString();
        }
    }
}

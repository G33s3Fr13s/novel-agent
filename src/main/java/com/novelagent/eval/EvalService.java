package com.novelagent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.entity.EvalResult;
import com.novelagent.repository.EvalResultRepository;
import com.novelagent.service.RetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG 评测：加载黄金测试集 → 跑检索 → 计算 Precision/Recall → 结果入库。
 *
 * 关键指标：
 *   vectorRecall   = 纯语义向量检索的 Recall（不开 GraphRAG 的 baseline）
 *   contextRecall  = 双路 RRF 融合后的 Recall
 *   graphragGain   = contextRecall - vectorRecall（回答"GraphRAG 有没有用"）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalService {

    private final RetrievalService retrievalService;
    private final EvalResultRepository evalResultRepo;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TESTSET_PATH = "eval/golden_testset.json";

    /** 跑一次评测并入库 */
    @Transactional
    public EvalResult run(String testsetPath) {
        String path = testsetPath != null ? testsetPath : TESTSET_PATH;
        log.info("=== RAG Eval START: {} ===", path);

        try {
            JsonNode root = objectMapper.readTree(new File(path));
            UUID novelId = UUID.fromString(root.get("novelId").asText());
            JsonNode questions = root.get("questions");

            // 预加载：所有 chunk 的 ID 和内容
            ChunkData chunkData = loadChunkData(novelId);
            log.info("Loaded {} chunks for annotation matching", chunkData.allChunks.size());

            double fusedPrecSum = 0, fusedRecSum = 0;
            double vectorPrecSum = 0, vectorRecSum = 0;
            List<Map<String, Object>> details = new ArrayList<>();
            int total = questions.size();

            for (JsonNode q : questions) {
                String qid = q.get("id").asText();
                String question = q.get("question").asText();
                String type = q.get("type").asText();
                boolean testGraphRAG = q.has("testGraphRAG") && q.get("testGraphRAG").asBoolean();

                Set<UUID> relevantIds = resolveChapters(q.get("expectedChunks"), chunkData);

                // 跑检索
                var result = retrievalService.retrieve(novelId, null, question);

                // 公平对比：统一取 top-8
                int topK = 8;
                Set<UUID> fusedIds = result.fused().stream().limit(topK).map(RetrievalService.ScoredChunk::id).collect(Collectors.toSet());
                Set<UUID> vectorIds = result.vectorOnly().stream().limit(topK).map(RetrievalService.ScoredChunk::id).collect(Collectors.toSet());

                double fPrec = precision(fusedIds, relevantIds);
                double fRec  = recall(fusedIds, relevantIds);
                double vPrec = precision(vectorIds, relevantIds);
                double vRec  = recall(vectorIds, relevantIds);

                fusedPrecSum += fPrec;
                fusedRecSum += fRec;
                vectorPrecSum += vPrec;
                vectorRecSum += vRec;

                Map<String, Object> d = new LinkedHashMap<>();
                d.put("id", qid);
                d.put("type", type);
                d.put("testGraphRAG", testGraphRAG);
                d.put("fusedPrecision", fPrec);
                d.put("fusedRecall", fRec);
                d.put("vectorPrecision", vPrec);
                d.put("vectorRecall", vRec);
                details.add(d);

                log.info("  {} [{}] GR={}: P(fused={:.2f}/{:.2f}) R(fused={:.2f}/{:.2f})",
                        qid, type, testGraphRAG, fPrec, vPrec, fRec, vRec);
            }

            double avgFusedPrec = fusedPrecSum / total;
            double avgFusedRec  = fusedRecSum / total;
            double avgVectorPrec = vectorPrecSum / total;
            double avgVectorRec  = vectorRecSum / total;
            double gain = avgFusedRec - avgVectorRec;

            EvalResult er = EvalResult.builder()
                    .testsetName(new File(path).getName().replace(".json", ""))
                    .novelId(novelId).totalQuestions(total)
                    .contextPrecision(round(avgFusedPrec))
                    .contextRecall(round(avgFusedRec))
                    .vectorPrecision(round(avgVectorPrec))
                    .vectorRecall(round(avgVectorRec))
                    .graphragGain(round(gain))
                    .details(objectMapper.writeValueAsString(details))
                    .build();
            evalResultRepo.save(er);

            log.info("=== RAG Eval DONE: fused P={:.2%} R={:.2%} | vector P={:.2%} R={:.2%} | gain={:+.2%} ===",
                    avgFusedPrec, avgFusedRec, avgVectorPrec, avgVectorRec, gain);
            return er;

        } catch (Exception e) {
            log.error("Eval failed", e);
            throw new RuntimeException("评测失败: " + e.getMessage(), e);
        }
    }

    /** 获取历史评测结果 */
    public List<EvalResult> getResults(int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        return evalResultRepo.findRecent(since);
    }

    // ── helpers ────────────────────────────────────────

    /** 加载所有 chunk 数据 */
    private ChunkData loadChunkData(UUID novelId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT c.chapter_num, ch.id AS chunk_id, ch.chunk_index, ch.content FROM chunks ch " +
                "JOIN chapters c ON c.id = ch.chapter_id WHERE c.novel_id = ?::uuid",
                novelId.toString());
        Map<String, Set<UUID>> keyed = new LinkedHashMap<>();
        List<ChunkRef> allChunks = new ArrayList<>();
        for (var row : rows) {
            UUID id = UUID.fromString(row.get("chunk_id").toString());
            String content = row.get("content") != null ? row.get("content").toString() : "";
            // 章节 key
            String chKey = "ch" + row.get("chapter_num");
            keyed.computeIfAbsent(chKey, k -> new LinkedHashSet<>()).add(id);
            // 索引 key (向后兼容)
            String preciseKey = chKey + ":" + row.get("chunk_index");
            keyed.computeIfAbsent(preciseKey, k -> new LinkedHashSet<>()).add(id);
            allChunks.add(new ChunkRef(id, content));
        }
        return new ChunkData(keyed, allChunks);
    }

    private record ChunkData(Map<String, Set<UUID>> keyed, List<ChunkRef> allChunks) {}
    private record ChunkRef(UUID id, String content) {}

    /**
     * 根据 expectedChunks 展开为所有 chunk ID。
     * 支持三种格式:
     *   "ch1"        — 整章所有 chunk
     *   "ch2:3"      — chunkIndex=3（向后兼容，重分块后失效）
     *   "三拳打死"    — 文本片段匹配（重分块稳定✅）
     */
    private Set<UUID> resolveChapters(JsonNode expectedChunks, ChunkData chunkData) {
        Set<UUID> ids = new LinkedHashSet<>();
        if (expectedChunks == null) return ids;
        for (JsonNode ch : expectedChunks) {
            String key = ch.asText();
            // 格式1 & 2: "chN" 或 "chN:M"
            Set<UUID> keyMatch = chunkData.keyed.get(key);
            if (keyMatch != null) {
                ids.addAll(keyMatch);
                continue;
            }
            // 格式3: 文本片段匹配
            for (ChunkRef ref : chunkData.allChunks) {
                if (ref.content.contains(key)) {
                    ids.add(ref.id);
                }
            }
        }
        return ids;
    }

    private double precision(Set<UUID> retrieved, Set<UUID> relevant) {
        if (retrieved.isEmpty() || relevant.isEmpty()) return 0;
        long hit = retrieved.stream().filter(relevant::contains).count();
        return (double) hit / retrieved.size();
    }

    private double recall(Set<UUID> retrieved, Set<UUID> relevant) {
        if (retrieved.isEmpty() || relevant.isEmpty()) return 0;
        long hit = retrieved.stream().filter(relevant::contains).count();
        return (double) hit / relevant.size();
    }

    private double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}

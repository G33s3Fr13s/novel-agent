package com.novelagent.service;

import com.novelagent.entity.Chapter;
import com.novelagent.entity.Chunk;
import com.novelagent.entity.Novel;
import com.novelagent.repository.ChapterRepository;
import com.novelagent.repository.ChunkRepository;
import com.novelagent.repository.NovelRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 语义分块核心算法：相邻段落向量相似度 → 检测跳变点 → 按语义边界切分。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticChunkService {

    private final NovelRepository novelRepository;
    private final ChapterRepository chapterRepository;
    private final ChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;

    private static final double SPLIT_THRESHOLD = 0.65;
    private static final int MAX_CHUNK_CHARS = 800;

    // ─── 全局分块（手动触发） ─────────────────────────────────

    /** 清空全部旧分块，对所有章节重新做一次全局语义分块。 */
    @Transactional
    public int chunkNovel(UUID novelId, UUID userId) {
        Novel novel = novelRepository.findByIdAndUserId(novelId, userId)
                .orElseThrow(() -> new RuntimeException("小说不存在或无权操作"));

        chunkRepository.deleteByNovelId(novelId);
        entityManager.flush();

        List<Chapter> chapters = chapterRepository.findByNovelIdOrderByChapterNumAsc(novelId);
        if (chapters.isEmpty()) throw new RuntimeException("小说还没有章节");

        log.info("=== 全局分块: novel={} chapters={} ===", novel.getTitle(), chapters.size());

        // 展平所有段落
        List<ParaRef> allParas = new ArrayList<>();
        for (Chapter ch : chapters) {
            List<String> paras = splitParagraphs(ch.getContent());
            for (int i = 0; i < paras.size(); i++) allParas.add(new ParaRef(ch, i, paras.get(i)));
        }
        if (allParas.size() < 2) return 0;

        // embed + 切分 + 存库
        float[][] embeddings = embeddingService.embedBatch(
                allParas.stream().map(p -> p.text).collect(Collectors.toList()));
        List<List<ParaRef>> groups = splitGroups(allParas, embeddings);
        groups = enforceMaxSize(groups);
        return saveChunks(novel, groups);
    }

    // ─── 单章增量分块（上传章节后自动调用） ──────────────────

    /**
     * 只对指定章节做分块，只删除该章的旧 chunk 然后重建。
     * 不做全量重建——全量重建走 {@link #chunkNovel}。
     */
    @Transactional
    public int chunkChapter(UUID novelId, UUID chapterId, UUID userId) {
        // 验证小说归属，防止跨用户数据篡改
        novelRepository.findByIdAndUserId(novelId, userId)
                .orElseThrow(() -> new RuntimeException("小说不存在或无权操作"));

        // 删旧
        List<Chunk> oldChunks = chunkRepository.findByChapterIdOrderByChunkIndexAsc(chapterId);
        if (!oldChunks.isEmpty()) {
            chunkRepository.deleteAll(oldChunks);
            entityManager.flush();
        }

        Novel novel = novelRepository.findById(novelId).orElseThrow();
        Chapter chapter = chapterRepository.findById(chapterId).orElseThrow();

        List<String> paras = splitParagraphs(chapter.getContent());
        if (paras.size() < 2) {
            // 只有一段，整个章节就是一个 chunk
            if (paras.size() == 1) {
                Chunk c = chunkRepository.saveAndFlush(Chunk.builder()
                        .novel(novel).chapter(chapter).chunkIndex(0)
                        .content(paras.get(0)).paraStart(0).paraEnd(0)
                        .charCount(paras.get(0).length()).build());
                float[] emb = embeddingService.embed(paras.get(0));
                saveEmbedding(c.getId(), emb);
                return 1;
            }
            return 0;
        }

        List<ParaRef> allParas = new ArrayList<>();
        for (int i = 0; i < paras.size(); i++) allParas.add(new ParaRef(chapter, i, paras.get(i)));

        float[][] embeddings = embeddingService.embedBatch(
                allParas.stream().map(p -> p.text).collect(Collectors.toList()));
        List<List<ParaRef>> groups = splitGroups(allParas, embeddings);
        groups = enforceMaxSize(groups);
        return saveChunks(novel, groups);
    }

    // ─── 核心切分算法 ────────────────────────────────────────

    /** 计算相邻相似度 → 找谷值 → 按谷值分组 */
    private List<List<ParaRef>> splitGroups(List<ParaRef> allParas, float[][] embeddings) {
        double[] sims = new double[allParas.size() - 1];
        for (int i = 0; i < sims.length; i++)
            sims[i] = cosineSimilarity(embeddings[i], embeddings[i + 1]);

        List<Integer> splitPoints = new ArrayList<>();
        for (int i = 0; i < sims.length; i++) {
            boolean loLeft  = i == 0 || sims[i] < sims[i - 1];
            boolean loRight = i == sims.length - 1 || sims[i] < sims[i + 1];
            if (loLeft && loRight && sims[i] < SPLIT_THRESHOLD) splitPoints.add(i);
        }

        List<List<ParaRef>> groups = new ArrayList<>();
        int start = 0;
        for (int sp : splitPoints) { groups.add(allParas.subList(start, sp + 1)); start = sp + 1; }
        groups.add(allParas.subList(start, allParas.size()));
        return groups;
    }

    /** 存 chunk —— 直接 embed chunk 文本内容，不做段落向量平均 */
    private int saveChunks(Novel novel, List<List<ParaRef>> groups) {
        // 构建 chunk 文本
        List<String> contents = new ArrayList<>();
        for (List<ParaRef> g : groups) {
            contents.add(g.stream().map(p -> p.text).collect(Collectors.joining("\n\n")));
        }
        // 批量 embed 所有 chunk 文本
        float[][] chunkEmbeddings = embeddingService.embedBatch(contents);

        int idx = 0;
        for (int i = 0; i < groups.size(); i++) {
            List<ParaRef> g = groups.get(i);
            String content = contents.get(i);
            Chunk c = chunkRepository.saveAndFlush(Chunk.builder()
                    .novel(novel).chapter(g.get(0).chapter).chunkIndex(idx++)
                    .content(content).paraStart(g.get(0).paraIdx).paraEnd(g.get(g.size() - 1).paraIdx)
                    .charCount(content.length()).build());
            saveEmbedding(c.getId(), chunkEmbeddings[i]);
        }
        return idx;
    }

    /** 把超过 MAX_CHUNK_CHARS 的组按段落边界拆分 */
    private List<List<ParaRef>> enforceMaxSize(List<List<ParaRef>> groups) {
        List<List<ParaRef>> result = new ArrayList<>();
        for (List<ParaRef> group : groups) {
            int totalChars = group.stream().mapToInt(p -> p.text.length()).sum();
            if (totalChars <= MAX_CHUNK_CHARS) {
                result.add(group);
            } else {
                List<ParaRef> current = new ArrayList<>();
                int curChars = 0;
                for (ParaRef p : group) {
                    if (curChars + p.text.length() > MAX_CHUNK_CHARS && !current.isEmpty()) {
                        result.add(new ArrayList<>(current));
                        current.clear();
                        curChars = 0;
                    }
                    current.add(p);
                    curChars += p.text.length();
                }
                if (!current.isEmpty()) result.add(current);
            }
        }
        return result;
    }

    // ─── 向量运算 ───────────────────────────────────────────

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, nA = 0, nB = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; nA += a[i] * a[i]; nB += b[i] * b[i]; }
        if (nA == 0 || nB == 0) return 0;
        return dot / (Math.sqrt(nA) * Math.sqrt(nB));
    }

    private void saveEmbedding(UUID chunkId, float[] vec) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) { if (i > 0) sb.append(","); sb.append(vec[i]); }
        sb.append("]");
        jdbcTemplate.update("UPDATE chunks SET embedding = ?::vector WHERE id = ?", sb.toString(), chunkId);
    }

    // ─── 段落拆分 ───────────────────────────────────────────

    private List<String> splitParagraphs(String content) {
        return Arrays.stream(content.split("(\\r?\\n){2,}")).map(String::strip).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }

    private record ParaRef(Chapter chapter, int paraIdx, String text) {}
}

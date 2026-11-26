package com.novelagent.controller;

import com.novelagent.dto.ApiResponse;
import com.novelagent.dto.NovelCreateRequest;
import com.novelagent.entity.Chapter;
import com.novelagent.entity.User;
import com.novelagent.repository.ChapterRepository;
import com.novelagent.repository.ChunkRepository;
import com.novelagent.repository.UserRepository;
import com.novelagent.service.NovelService;
import com.novelagent.service.GraphBuildService;
import com.novelagent.service.PlotBuildService;
import com.novelagent.service.SemanticChunkService;
import com.novelagent.service.ExportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/novels")
@RequiredArgsConstructor
public class NovelController {

    private final NovelService novelService;
    private final SemanticChunkService semanticChunkService;
    private final GraphBuildService graphBuildService;
    private final PlotBuildService plotBuildService;
    private final ExportService exportService;
    private final UserRepository userRepository;
    private final ChapterRepository chapterRepository;
    private final ChunkRepository chunkRepository;

    /** POST /api/novels — 创建小说 */
    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @Valid @RequestBody NovelCreateRequest request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        User user = userRepository.findByUsername(userDetails.getUsername()).orElseThrow();
        var novel = novelService.createNovel(request, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(Map.of(
                "id", novel.getId().toString(), "title", novel.getTitle()
        )));
    }

    /** GET /api/novels — 我的小说列表 */
    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        User user = userRepository.findByUsername(userDetails.getUsername()).orElseThrow();
        return ResponseEntity.ok(ApiResponse.success(novelService.getNovels(user)));
    }

    /**
     * POST /api/novels/{id}/chapters
     * 上传章节文件（txt/md）→ 保存 → 自动触发语义分块。
     *
     * 表单字段:
     *   file        — .txt 或 .md 文件
     *   chapterNum  — 章节号（可选，不传则从文件名推断）
     *   title       — 章节标题（可选，不传则从正文首行推断）
     */
    @PostMapping(value = "/{id}/chapters", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadChapter(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "chapterNum", required = false) Integer chapterNum,
            @RequestParam(value = "title", required = false) String title,
            @AuthenticationPrincipal UserDetails userDetails
    ) throws IOException {
        User user = userRepository.findByUsername(userDetails.getUsername()).orElseThrow();

        // 1. 保存章节（支持整本拆分，返回多章）
        List<Chapter> chapters = novelService.uploadChapter(id, file, chapterNum, title, user);

        // 2. 对每章增量分块 + 增量图谱
        List<Map<String, Object>> chapterResults = new ArrayList<>();
        for (Chapter ch : chapters) {
            int chunkCount;
            try {
                chunkCount = semanticChunkService.chunkChapter(id, ch.getId(), user.getId());
            } catch (Exception e) {
                log.warn("Chunking failed for ch{} (non-fatal): {}", ch.getChapterNum(), e.getMessage());
                chunkCount = 0;
            }
            try {
                graphBuildService.updateGraphForChapter(id, ch.getId(), user.getId());
            } catch (Exception e) {
                log.warn("Graph update failed for ch{} (non-fatal): {}", ch.getChapterNum(), e.getMessage());
            }

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", ch.getId().toString());
            r.put("chapterNum", ch.getChapterNum());
            r.put("title", ch.getTitle());
            r.put("charCount", ch.getCharCount());
            r.put("paragraphCount", ch.getParagraphCount());
            r.put("chunksCreated", chunkCount);
            chapterResults.add(r);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chapters", chapterResults);
        result.put("totalChaptersCreated", chapters.size());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * POST /api/novels/{id}/chapters/batch
     * 批量上传目录中的 .txt 文件。文件名格式: "第X回_标题.txt" 或 "楔子_标题.txt"
     *
     * 支持两种传参方式:
     *   - Query param: ?directoryPath=D:/novel-agent/chapters/shuihu
     *   - JSON body:   { "directoryPath": "D:\\novel-agent\\chapters\\shuihu" }
     */
    @PostMapping(value = "/{id}/chapters/batch")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadBatch(
            @PathVariable UUID id,
            @RequestParam(value = "directoryPath", required = false) String dirParam,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal UserDetails userDetails
    ) throws IOException {
        User user = userRepository.findByUsername(userDetails.getUsername()).orElseThrow();
        String directoryPath = dirParam;
        if (directoryPath == null && body != null) {
            directoryPath = body.get("directoryPath");
        }
        if (directoryPath == null || directoryPath.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "directoryPath is required (query param or JSON body)"));
        }

        // 1. 批量保存章节
        List<Map<String, Object>> results = novelService.uploadChaptersFromDirectory(id, directoryPath, user);

        long okCount = results.stream().filter(r -> "ok".equals(r.get("status"))).count();

        // 2. 对每章触发分块 + 图谱（仅对新上传的）
        int chunked = 0;
        int graphBuilt = 0;
        for (Map<String, Object> r : results) {
            if (!"ok".equals(r.get("status"))) continue;
            int chNum = (int) r.get("chapterNum");
            try {
                var chOpt = chapterRepository.findByNovelIdAndChapterNum(id, chNum);
                if (chOpt.isPresent()) {
                    UUID chId = chOpt.get().getId();
                    try {
                        chunked += semanticChunkService.chunkChapter(id, chId, user.getId());
                    } catch (Exception e) {
                        log.warn("Chunk failed for ch{}: {}", chNum, e.getMessage());
                    }
                    try {
                        graphBuildService.updateGraphForChapter(id, chId, user.getId());
                        graphBuilt++;
                    } catch (Exception e) {
                        log.warn("Graph failed for ch{}: {}", chNum, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("Process failed for ch{}: {}", chNum, e.getMessage());
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("totalFiles", results.size());
        resp.put("uploaded", okCount);
        resp.put("skipped", results.stream().filter(r -> "skip".equals(r.get("status"))).count());
        resp.put("failed", results.stream().filter(r -> "fail".equals(r.get("status"))).count());
        resp.put("chunked", chunked);
        resp.put("graphBuilt", graphBuilt);
        resp.put("details", results);

        return ResponseEntity.ok(ApiResponse.success(resp));
    }

    /** POST /api/novels/{id}/chunk — 手动触发语义分块 */
    @PostMapping("/{id}/chunk")
    public ResponseEntity<ApiResponse<Map<String, Object>>> chunkNovel(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        User user = userRepository.findByUsername(userDetails.getUsername()).orElseThrow();
        int count = semanticChunkService.chunkNovel(id, user.getId());
        return ResponseEntity.ok(ApiResponse.success(Map.of("chunks", count)));
    }

    /** POST /api/novels/{id}/graph — 异步构建知识图谱，立即返回 202 */
    @PostMapping("/{id}/graph")
    public ResponseEntity<ApiResponse<Map<String, Object>>> buildGraph(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        User user = userRepository.findByUsername(userDetails.getUsername()).orElseThrow();
        graphBuildService.buildGraphAsync(id, user.getId());
        return ResponseEntity.accepted()
                .body(ApiResponse.success(Map.of("status", "building")));
    }

    /** POST /api/novels/{id}/plot — 异步构建剧情事件，立即返回 202 */
    @PostMapping("/{id}/plot")
    public ResponseEntity<ApiResponse<Map<String, Object>>> buildPlot(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        User user = userRepository.findByUsername(userDetails.getUsername()).orElseThrow();
        plotBuildService.buildPlotAsync(id, user.getId());
        return ResponseEntity.accepted()
                .body(ApiResponse.success(Map.of("status", "building")));
    }

    /** GET /api/novels/{id}/plot — 导出剧情大纲 */
    @GetMapping("/{id}/plot")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getPlot(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        User user = userRepository.findByUsername(userDetails.getUsername()).orElseThrow();
        return ResponseEntity.ok(ApiResponse.success(plotBuildService.getPlot(id, user.getId())));
    }

    /** GET /api/novels/{id}/graph — 导出知识图谱数据 */
    @GetMapping("/{id}/graph")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getGraph(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        User user = userRepository.findByUsername(userDetails.getUsername()).orElseThrow();
        return ResponseEntity.ok(ApiResponse.success(novelService.getGraph(id, user.getId())));
    }

    /** GET /api/novels/{id}/chapters — 章节列表 */
    @GetMapping("/{id}/chapters")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> chapters(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        User user = userRepository.findByUsername(userDetails.getUsername()).orElseThrow();
        return ResponseEntity.ok(ApiResponse.success(novelService.getChapters(id, user)));
    }

    /** DELETE /api/novels/{novelId}/chapters/{chapterId} — 删除章节 */
    @DeleteMapping("/{novelId}/chapters/{chapterId}")
    public ResponseEntity<ApiResponse<Void>> deleteChapter(
            @PathVariable UUID novelId,
            @PathVariable UUID chapterId,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        User user = userRepository.findByUsername(userDetails.getUsername()).orElseThrow();
        novelService.deleteChapter(novelId, chapterId, user);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /** GET /api/novels/{id}/export/plot — 导出剧情脉络 */
    @GetMapping(value = "/{id}/export/plot", produces = "text/markdown; charset=UTF-8")
    public ResponseEntity<?> exportPlot(
            @PathVariable UUID id, @AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepository.findByUsername(userDetails.getUsername()).orElseThrow();
        novelService.getChapters(id, user);
        try {
            String md = exportService.exportPlot(id);
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"plot.md\"")
                    .body(md.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Plot export failed for novel {}", id, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "导出失败: " + e.getMessage()));
        }
    }

    /** GET /api/novels/{id}/export/chars — 导出人物关系 */
    @GetMapping(value = "/{id}/export/chars", produces = "text/markdown; charset=UTF-8")
    public ResponseEntity<?> exportCharacters(
            @PathVariable UUID id, @AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepository.findByUsername(userDetails.getUsername()).orElseThrow();
        novelService.getChapters(id, user);
        try {
            String md = exportService.exportCharacters(id);
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"characters.md\"")
                    .body(md.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Character export failed for novel {}", id, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "导出失败: " + e.getMessage()));
        }
    }

    /** DELETE /api/novels/{id} — 删除小说及所有关联数据 */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteNovel(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        User user = userRepository.findByUsername(userDetails.getUsername()).orElseThrow();
        novelService.deleteNovel(id, user);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /** GET /api/novels/{id}/chapters/{num} — 读取章节正文 */
    @GetMapping("/{id}/chapters/{num}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> chapterContent(
            @PathVariable UUID id,
            @PathVariable int num,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        User user = userRepository.findByUsername(userDetails.getUsername()).orElseThrow();
        Chapter ch = novelService.getChapterContent(id, num, user);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", ch.getId().toString());
        result.put("chapterNum", ch.getChapterNum());
        result.put("title", ch.getTitle());
        result.put("content", ch.getContent());
        result.put("charCount", ch.getCharCount());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /** GET /api/novels/{id}/chapters/{num}/chunks — 章节的 chunk 列表（含段落范围） */
    @GetMapping("/{id}/chapters/{num}/chunks")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> chapterChunks(
            @PathVariable UUID id,
            @PathVariable int num,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        User user = userRepository.findByUsername(userDetails.getUsername()).orElseThrow();
        Chapter ch = novelService.getChapterContent(id, num, user);
        List<Map<String, Object>> list = chunkRepository.findByChapterIdOrderByChunkIndexAsc(ch.getId())
                .stream().map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("chunkIndex", c.getChunkIndex());
                    m.put("paraStart", c.getParaStart());
                    m.put("paraEnd", c.getParaEnd());
                    m.put("charCount", c.getCharCount());
                    m.put("contentPreview", c.getContent() != null && c.getContent().length() > 80
                            ? c.getContent().substring(0, 80) + "..." : c.getContent());
                    return m;
                }).toList();
        return ResponseEntity.ok(ApiResponse.success(list));
    }
}

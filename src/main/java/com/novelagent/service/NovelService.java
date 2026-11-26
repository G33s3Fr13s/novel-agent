package com.novelagent.service;

import com.novelagent.dto.NovelCreateRequest;
import com.novelagent.entity.Chapter;
import com.novelagent.entity.Novel;
import com.novelagent.entity.User;
import com.novelagent.repository.ChapterRepository;
import com.novelagent.repository.ChunkRepository;
import com.novelagent.repository.GraphEntityRepository;
import com.novelagent.repository.GraphRelationshipRepository;
import com.novelagent.repository.NovelRepository;
import com.novelagent.repository.PlotEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class NovelService {

    private final NovelRepository novelRepository;
    private final ChapterRepository chapterRepository;
    private final ChunkRepository chunkRepository;
    private final GraphEntityRepository graphEntityRepo;
    private final GraphRelationshipRepository graphRelationshipRepo;
    private final PlotEventRepository plotEventRepo;

    /** 支持的文件格式 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("txt", "md", "docx");

    /** 从文件名提取章节号: "chapter_03.txt" / "ch03.md" / "03.txt" → 3 */
    private static final Pattern FILENAME_NUM = Pattern.compile("(\\d+)");

    /** 从正文首行提取标题: "第N章 XXX" → "XXX" */
    private static final Pattern FIRST_LINE_TITLE = Pattern.compile("^第[\\d一二三四五六七八九十百千万]+章\\s*(.+)");

    /** 章节标记: 第X章（支持中文数字和阿拉伯数字） */
    private static final Pattern CHAPTER_MARKER = Pattern.compile("第([\\d一二三四五六七八九十百千万]+)章");
    private static final Pattern CHAPTER_MARKER_ARABIC = Pattern.compile("第(\\d+)章");

    @Transactional
    public Novel createNovel(NovelCreateRequest request, User user) {
        Novel novel = Novel.builder()
                .user(user)
                .title(request.getTitle())
                .author(request.getAuthor())
                .description(request.getDescription())
                .build();
        return novelRepository.save(novel);
    }

    /**
     * 上传章节文件 → 自动检测整本小说并拆分。
     *
     * 支持格式: .txt / .md / .docx
     * 整本检测: 含多个 "第X章" 标记 → 自动拆分成多章
     *
     * @return 创建的章节列表（整本拆分时返回多章）
     */
    @Transactional
    public List<Chapter> uploadChapter(UUID novelId, MultipartFile file,
                                       Integer chapterNum, String title,
                                       User user) throws IOException {
        // 校验文件类型
        String originalName = file.getOriginalFilename();
        String ext = getExtension(originalName).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new RuntimeException("不支持的文件格式: ." + ext + "，仅支持: " + ALLOWED_EXTENSIONS);
        }

        Novel novel = novelRepository.findByIdAndUserId(novelId, user.getId())
                .orElseThrow(() -> new RuntimeException("小说不存在或无权操作"));

        // 读取文本内容
        String rawContent = readFileContent(file, ext);

        // 检测是否为整本小说（含多个章节标记）
        List<ChapterSegment> segments = splitChapters(rawContent);

        if (segments.size() > 1) {
            // 整本小说 → 拆分上传
            log.info("Detected full novel with {} chapters, splitting...", segments.size());
            List<Chapter> saved = new ArrayList<>();
            int nextChapterNum = chapterNum != null ? chapterNum
                    : (int) chapterRepository.countByNovelId(novelId) + 1;
            for (ChapterSegment seg : segments) {
                Chapter ch = saveChapter(novel, seg.title, seg.content, nextChapterNum++);
                saved.add(ch);
            }
            log.info("Full novel split done: {} chapters created", saved.size());
            return saved;
        }

        // 单章上传
        ChapterSegment seg = segments.isEmpty() ? new ChapterSegment(title, rawContent) : segments.get(0);

        // 自动推断章节号
        if (chapterNum == null) {
            chapterNum = extractChapterNum(originalName);
            if (chapterNum == null) {
                chapterNum = (int) chapterRepository.countByNovelId(novelId) + 1;
            }
        }

        // 自动推断标题
        String finalTitle = (title != null && !title.isBlank()) ? title : seg.title;
        if (finalTitle == null || finalTitle.isBlank()) {
            finalTitle = "第" + chapterNum + "章";
        }

        Chapter saved = saveChapter(novel, finalTitle, seg.content, chapterNum);
        log.info("Chapter uploaded: novel={} ch={} file={} chars={}",
                novel.getTitle(), chapterNum, originalName, saved.getCharCount());
        return List.of(saved);
    }

    /**
     * 批量上传目录中的所有 .txt 文件。
     * 文件名格式: "第X回_标题.txt" 或 "楔子_标题.txt"
     * 章节号从文件名推断。
     *
     * @return 每项: {chapterNum, title, status: "ok"/"skip"/"fail", error?}
     */
    @Transactional
    public List<Map<String, Object>> uploadChaptersFromDirectory(UUID novelId, String directoryPath, User user) throws IOException {
        Novel novel = novelRepository.findByIdAndUserId(novelId, user.getId())
                .orElseThrow(() -> new RuntimeException("小说不存在或无权操作"));

        Path dir = Paths.get(directoryPath);
        if (!Files.isDirectory(dir)) {
            throw new RuntimeException("目录不存在: " + directoryPath);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int nextChapterNum = (int) chapterRepository.countByNovelId(novelId) + 1;

        try (var stream = Files.list(dir)) {
            List<Path> txtFiles = stream
                    .filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .sorted((a, b) -> {
                        // Sort by parsed chapter number
                        int na = parseChapterNumFromFilename(a.getFileName().toString());
                        int nb = parseChapterNumFromFilename(b.getFileName().toString());
                        return Integer.compare(na, nb);
                    })
                    .toList();

            for (Path filePath : txtFiles) {
                String filename = filePath.getFileName().toString();
                Map<String, Object> r = new LinkedHashMap<>();
                try {
                    int chNum = parseChapterNumFromFilename(filename);
                    String title = extractTitleFromFilename(filename);

                    // Skip if already exists
                    if (chapterRepository.findByNovelIdAndChapterNum(novelId, chNum).isPresent()) {
                        r.put("chapterNum", chNum);
                        r.put("status", "skip");
                        results.add(r);
                        continue;
                    }

                    String content = Files.readString(filePath, StandardCharsets.UTF_8);
                    // Clean metadata prefix if present (e.g., "---\n...\n---")
                    content = content.replaceFirst("(?s)^---\\n.*?---\\n+", "");

                    Chapter saved = saveChapter(novel, title, content, chNum);
                    r.put("chapterNum", saved.getChapterNum());
                    r.put("title", saved.getTitle());
                    r.put("charCount", saved.getCharCount());
                    r.put("status", "ok");
                    results.add(r);
                    log.info("Batch uploaded: novel={} ch={} file={} chars={}",
                            novel.getTitle(), chNum, filename, saved.getCharCount());
                } catch (Exception e) {
                    r.put("chapterNum", parseChapterNumFromFilename(filename));
                    r.put("status", "fail");
                    r.put("error", e.getMessage());
                    results.add(r);
                    log.error("Batch upload failed for {}: {}", filename, e.getMessage());
                }
            }
        }
        return results;
    }

    /** Parse chapter number from Chinese filename like "第一百二十回_XXX.txt" or "楔子_XXX.txt" */
    private int parseChapterNumFromFilename(String filename) {
        String base = filename.replace(".txt", "");
        String prefix = base.contains("_") ? base.split("_")[0] : base;

        if (prefix.equals("楔子")) return 0;

        // Remove "第" and "回"
        String numStr = prefix.replaceFirst("^第", "").replaceFirst("回$", "");
        if (numStr.isEmpty()) return -1;

        // Try Arabic first
        try { return Integer.parseInt(numStr); } catch (NumberFormatException ignored) {}

        return parseChineseNumber(numStr);
    }

    /** Extract title from filename like "第X回_标题_副标题.txt" → "标题 副标题" */
    private String extractTitleFromFilename(String filename) {
        String base = filename.replace(".txt", "");
        int idx = base.indexOf('_');
        if (idx < 0) return base;
        return base.substring(idx + 1).replace('_', ' ').trim();
    }

    /** 保存一个章节到数据库 */
    private Chapter saveChapter(Novel novel, String title, String content, int chapterNum) {
        // 去掉正文首行的章标题行（避免重复存储）
        String cleanContent = content.replaceFirst("^第[\\d一二三四五六七八九十百千万]+章.*\\n+", "").trim();

        Chapter chapter = chapterRepository
                .findByNovelIdAndChapterNum(novel.getId(), chapterNum)
                .orElseGet(() -> Chapter.builder()
                        .novel(novel)
                        .chapterNum(chapterNum)
                        .build());

        chapter.setTitle(title);
        chapter.setContent(cleanContent);
        chapter.setCharCount(countChars(cleanContent));
        chapter.setParagraphCount(countParagraphs(cleanContent));
        return chapterRepository.save(chapter);
    }

    // ─── 文件读取 ───────────────────────────────────────────────

    private String readFileContent(MultipartFile file, String ext) throws IOException {
        if ("docx".equals(ext)) {
            return extractDocxText(file);
        }
        return new String(file.getBytes(), StandardCharsets.UTF_8);
    }

    /** Apache POI 提取 .docx 纯文本 */
    private String extractDocxText(MultipartFile file) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(file.getInputStream())) {
            StringBuilder sb = new StringBuilder();
            List<XWPFParagraph> paragraphs = doc.getParagraphs();
            for (XWPFParagraph p : paragraphs) {
                String text = p.getText();
                if (text != null && !text.isBlank()) {
                    sb.append(text).append('\n');
                } else {
                    sb.append('\n'); // 空行保留，用于段落计数
                }
            }
            return sb.toString();
        }
    }

    // ─── 整本小说拆分 ──────────────────────────────────────────

    /** 章节片段 */
    private static class ChapterSegment {
        String title;
        String content;
        ChapterSegment(String title, String content) {
            this.title = title;
            this.content = content;
        }
    }

    /**
     * 检测文本中的章节标记并拆分。
     * 如果有 ≥2 个 "第X章" → 返回多个片段；否则返回空或单条。
     */
    private List<ChapterSegment> splitChapters(String text) {
        List<ChapterSegment> result = new ArrayList<>();

        // 找到所有章节标记的位置
        List<int[]> markers = new ArrayList<>(); // [start, end, chapterNum]
        Matcher m = CHAPTER_MARKER.matcher(text);
        while (m.find()) {
            int num = parseChapterNum(m.group(1));
            if (num > 0) {
                markers.add(new int[]{m.start(), m.end(), num});
            }
        }

        if (markers.size() < 2) {
            // 单章或没有标记，不做拆分
            return result;
        }

        // 按章节标记切分
        for (int i = 0; i < markers.size(); i++) {
            int[] marker = markers.get(i);
            int contentStart = marker[1]; // 标记结束位置（换行符之后）
            // 跳过标记行尾的换行符
            while (contentStart < text.length() && (text.charAt(contentStart) == '\n' || text.charAt(contentStart) == '\r')) {
                contentStart++;
            }
            int contentEnd = (i + 1 < markers.size()) ? markers.get(i + 1)[0] : text.length();

            String titleLine = text.substring(marker[0], marker[1]).trim();
            // 提取标题正文: "第3章 初窥星辰" → "第3章 初窥星辰"
            Matcher titleM = FIRST_LINE_TITLE.matcher(titleLine);
            String title = titleM.find() ? titleLine : "第" + marker[2] + "章";

            String content = text.substring(contentStart, contentEnd).trim();
            if (!content.isBlank()) {
                result.add(new ChapterSegment(title, content));
            }
        }

        return result;
    }

    /** 解析中文数字或阿拉伯数字: "十三"→13, "一百二十三"→123, "3"→3 */
    private int parseChapterNum(String raw) {
        // 先试阿拉伯数字
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {}

        // 中文数字解析
        return parseChineseNumber(raw);
    }

    private static final Map<Character, Integer> CN_NUM = Map.ofEntries(
            Map.entry('零', 0), Map.entry('一', 1), Map.entry('二', 2),
            Map.entry('三', 3), Map.entry('四', 4), Map.entry('五', 5),
            Map.entry('六', 6), Map.entry('七', 7), Map.entry('八', 8),
            Map.entry('九', 9), Map.entry('十', 10),
            Map.entry('百', 100), Map.entry('千', 1000), Map.entry('万', 10000)
    );

    private int parseChineseNumber(String cn) {
        int result = 0;
        int current = 0;
        for (char c : cn.toCharArray()) {
            Integer val = CN_NUM.get(c);
            if (val == null) continue;
            if (val >= 10) {
                if (current == 0) current = 1;
                result += current * val;
                current = 0;
            } else {
                current = val;
            }
        }
        result += current;
        return result;
    }

    // ─── 已发布方法 ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getNovels(User user) {
        return novelRepository.findByUserIdOrderByUpdatedAtDesc(user.getId()).stream()
                .map(n -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", n.getId().toString());
                    m.put("title", n.getTitle());
                    m.put("author", n.getAuthor());
                    m.put("description", n.getDescription());
                    m.put("chapterCount", n.getChapters().size());
                    m.put("createdAt", n.getCreatedAt());
                    m.put("updatedAt", n.getUpdatedAt());
                    return m;
                }).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getChapters(UUID novelId, User user) {
        novelRepository.findByIdAndUserId(novelId, user.getId())
                .orElseThrow(() -> new RuntimeException("小说不存在或无权操作"));
        return chapterRepository.findByNovelIdOrderByChapterNumAsc(novelId).stream()
                .map(ch -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", ch.getId().toString());
                    m.put("chapterNum", ch.getChapterNum());
                    m.put("title", ch.getTitle());
                    m.put("charCount", ch.getCharCount());
                    m.put("paragraphCount", ch.getParagraphCount());
                    m.put("createdAt", ch.getCreatedAt());
                    return m;
                }).toList();
    }

    @Transactional(readOnly = true)
    public Chapter getChapterContent(UUID novelId, int chapterNum, User user) {
        novelRepository.findByIdAndUserId(novelId, user.getId())
                .orElseThrow(() -> new RuntimeException("小说不存在或无权操作"));
        return chapterRepository.findByNovelIdAndChapterNum(novelId, chapterNum)
                .orElseThrow(() -> new RuntimeException("章节不存在"));
    }

    // ─── 图谱导出 ──────────────────────────────────────────────

    public Map<String, Object> getGraph(UUID novelId, UUID userId) {
        novelRepository.findByIdAndUserId(novelId, userId)
                .orElseThrow(() -> new RuntimeException("小说不存在或无权操作"));

        var entities = graphEntityRepo.findByNovelId(novelId).stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.getName());
                    m.put("type", e.getType());
                    m.put("aliases", e.getAliases());
                    m.put("description", e.getDescription());
                    return m;
                }).toList();

        var relations = graphRelationshipRepo.findByNovelId(novelId).stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("source", r.getSource().getName());
                    m.put("target", r.getTarget().getName());
                    m.put("relationType", r.getRelationType());
                    m.put("description", r.getDescription());
                    m.put("weight", r.getWeight());
                    return m;
                }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entities", entities);
        result.put("relations", relations);
        return result;
    }

    // ─── 删除 ──────────────────────────────────────────────────

    @Transactional
    public void deleteChapter(UUID novelId, UUID chapterId, User user) {
        novelRepository.findByIdAndUserId(novelId, user.getId())
                .orElseThrow(() -> new RuntimeException("小说不存在或无权操作"));
        Chapter chapter = chapterRepository.findById(chapterId)
                .orElseThrow(() -> new RuntimeException("章节不存在"));
        if (!chapter.getNovel().getId().equals(novelId)) {
            throw new RuntimeException("章节不属于该小说");
        }
        // 清理关联数据
        chunkRepository.deleteByChapterId(chapterId);
        plotEventRepo.deleteByChapterId(chapterId);
        chapterRepository.delete(chapter);
        log.info("Chapter deleted: novel={} ch={}", novelId, chapter.getChapterNum());
    }

    @Transactional
    public void deleteNovel(UUID novelId, User user) {
        novelRepository.findByIdAndUserId(novelId, user.getId())
                .orElseThrow(() -> new RuntimeException("小说不存在或无权操作"));
        // 清理所有关联数据
        chunkRepository.deleteByNovelId(novelId);
        plotEventRepo.deleteByNovelId(novelId);
        graphRelationshipRepo.deleteByNovelId(novelId);
        graphEntityRepo.deleteByNovelId(novelId);
        chapterRepository.findByNovelIdOrderByChapterNumAsc(novelId).forEach(chapterRepository::delete);
        novelRepository.deleteById(novelId);
        log.info("Novel deleted: id={}", novelId);
    }

    // ─── 文件/文本辅助 ────────────────────────────────────────

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "";
        return filename.substring(filename.lastIndexOf('.') + 1);
    }

    private Integer extractChapterNum(String filename) {
        Matcher m = FILENAME_NUM.matcher(filename);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private int countChars(String text) {
        if (text == null || text.isBlank()) return 0;
        int count = 0;
        for (char c : text.toCharArray()) {
            var block = Character.UnicodeBlock.of(c);
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) {
                count++;
            }
        }
        return count;
    }

    private int countParagraphs(String text) {
        if (text == null || text.isBlank()) return 0;
        return (int) Arrays.stream(text.split("(\\r?\\n){2,}"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .count();
    }
}

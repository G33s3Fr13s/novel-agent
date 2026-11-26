package com.novelagent.service;

import com.novelagent.entity.GraphEntity;
import com.novelagent.entity.GraphRelationship;
import com.novelagent.entity.Novel;
import com.novelagent.repository.GraphEntityRepository;
import com.novelagent.repository.GraphRelationshipRepository;
import com.novelagent.repository.NovelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 小说导出服务：人物关系图 + 剧情大纲 → Markdown
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportService {

    private final NovelRepository novelRepository;
    private final GraphEntityRepository entityRepo;
    private final GraphRelationshipRepository relationRepo;
    private final PlotBuildService plotBuildService;

    @Transactional(readOnly = true)
    public String exportPlot(UUID novelId) {
        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new RuntimeException("小说不存在"));
        String outline = plotBuildService.toOutlineText(novelId);

        StringBuilder md = new StringBuilder();
        md.append("# 《").append(novel.getTitle()).append("》剧情脉络\n\n");
        md.append("> 导出时间：").append(java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n\n");
        if (outline != null && !outline.isBlank()) {
            md.append(outline).append("\n\n");
        } else {
            md.append("*暂无剧情数据，请先使用「分析剧情结构」*\n\n");
        }
        return md.toString();
    }

    @Transactional(readOnly = true)
    public String exportCharacters(UUID novelId) {
        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new RuntimeException("小说不存在"));

        List<GraphEntity> entities = entityRepo.findByNovelId(novelId);
        List<GraphRelationship> relations = new ArrayList<>();
        for (GraphEntity e : entities) {
            relations.addAll(relationRepo.findBySourceIdOrTargetId(e.getId(), e.getId()));
        }
        relations = new ArrayList<>(new LinkedHashSet<>(relations));

        StringBuilder md = new StringBuilder();
        md.append("# 《").append(novel.getTitle()).append("》人物关系\n\n");
        md.append("> 导出时间：").append(java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n\n");

        Map<String, List<GraphEntity>> byType = entities.stream()
                .collect(Collectors.groupingBy(GraphEntity::getType, LinkedHashMap::new, Collectors.toList()));

        for (var entry : byType.entrySet()) {
            String typeLabel = typeLabel(entry.getKey());
            md.append("## ").append(typeLabel).append("\n\n");
            for (GraphEntity e : entry.getValue()) {
                md.append("- **").append(e.getName()).append("**");
                if (e.getAliases() != null && !e.getAliases().isEmpty()) {
                    md.append("（").append(String.join("、", e.getAliases())).append("）");
                }
                if (e.getDescription() != null && !e.getDescription().isBlank()) {
                    md.append(": ").append(e.getDescription());
                }
                md.append("\n");
            }
            md.append("\n");
        }

        if (!relations.isEmpty()) {
            md.append("## 关系网络\n\n");
            for (GraphRelationship r : relations) {
                md.append("- ").append(r.getSource().getName())
                        .append(" → ").append(r.getTarget().getName())
                        .append(": **").append(r.getRelationType()).append("**");
                if (r.getDescription() != null && !r.getDescription().isBlank()) {
                    md.append("（").append(r.getDescription()).append("）");
                }
                md.append("\n");
            }
            md.append("\n");
        }

        if (entities.isEmpty() && relations.isEmpty()) {
            md.append("*暂无人物数据，请先使用「分析人物关系」*\n\n");
        }
        return md.toString();
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
}

package com.novelagent.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "continue_jobs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ContinueJob {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "novel_id", nullable = false)
    private Novel novel;

    /** 小说标题（冗余存一份，方便列表展示不用 join） */
    @Column(name = "novel_title", nullable = false, length = 500)
    private String novelTitle;

    /** 参考章节 ID，逗号分隔 */
    @Column(name = "chapter_ids", columnDefinition = "TEXT")
    private String chapterIds;

    /** 参考章节标题，逗号分隔（冗余，方便预览展示） */
    @Column(name = "chapter_titles", columnDefinition = "TEXT")
    private String chapterTitles;

    /** 续写章数 */
    @Column(name = "chapter_count", nullable = false)
    private int chapterCount;

    /** 前言（前置背景） */
    @Column(columnDefinition = "TEXT")
    private String foreword;

    /** 后语（续写方向） */
    @Column(columnDefinition = "TEXT")
    private String afterword;

    /** 额外提示词 */
    @Column(columnDefinition = "TEXT")
    private String prompt;

    /** 联网搜索站点 key，逗号分隔 */
    @Column(name = "search_sites", columnDefinition = "TEXT")
    private String searchSites;

    /** 联网搜索提示词 */
    @Column(name = "search_hint", columnDefinition = "TEXT")
    private String searchHint;

    /** 续写结果全文 */
    @Column(name = "result", columnDefinition = "TEXT")
    private String result;

    /** 结果字数 */
    @Column(name = "result_length")
    private int resultLength;

    /** 状态：GENERATING / COMPLETED / FAILED */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "COMPLETED";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

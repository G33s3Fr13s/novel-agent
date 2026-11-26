package com.novelagent.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "eval_results")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EvalResult {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "testset_name", nullable = false, length = 200)
    private String testsetName;

    @Column(name = "novel_id", nullable = false)
    private UUID novelId;

    @Column(name = "total_questions", nullable = false)
    private int totalQuestions;

    /** 双路融合检索 Precision */
    @Column(name = "context_precision")
    private double contextPrecision;

    /** 双路融合检索 Recall */
    @Column(name = "context_recall")
    private double contextRecall;

    /** 纯语义向量检索 Precision（baseline） */
    @Column(name = "vector_precision")
    private double vectorPrecision;

    /** 纯语义向量检索 Recall（baseline） */
    @Column(name = "vector_recall")
    private double vectorRecall;

    /** GraphRAG 增益：fusedRecall - vectorRecall */
    @Column(name = "graphrag_gain")
    private double graphragGain;

    /** 逐题明细 (JSONB) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String details;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

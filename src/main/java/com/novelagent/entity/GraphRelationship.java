package com.novelagent.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "graph_relationships")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class GraphRelationship {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 关系起点实体 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_entity_id", nullable = false)
    private GraphEntity source;

    /** 关系终点实体 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_entity_id", nullable = false)
    private GraphEntity target;

    /** 关系类型: "师徒" / "敌对" / "拥有" / "位于" */
    @Column(name = "relation_type", nullable = false, length = 100)
    private String relationType;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** 关系权重 0~1，越高越重要 */
    @Column(nullable = false)
    @Builder.Default
    private float weight = 1.0f;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

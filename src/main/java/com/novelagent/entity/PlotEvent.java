package com.novelagent.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "plot_events")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PlotEvent {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "novel_id", nullable = false)
    private Novel novel;

    @Column(name = "event_index", nullable = false)
    private int eventIndex;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chapter_id")
    private Chapter chapter;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Column(name = "key_entities", columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Builder.Default
    private List<String> keyEntities = new ArrayList<>();

    @Column(name = "event_type", nullable = false, length = 30)
    @Builder.Default
    private String eventType = "PLOT";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

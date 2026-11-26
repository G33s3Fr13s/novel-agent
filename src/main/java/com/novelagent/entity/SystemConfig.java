package com.novelagent.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "system_config")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SystemConfig {

    @Id
    @Column(name = "key", length = 100)
    private String key;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String value;
}

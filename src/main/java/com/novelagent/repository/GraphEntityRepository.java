package com.novelagent.repository;

import com.novelagent.entity.GraphEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GraphEntityRepository extends JpaRepository<GraphEntity, UUID> {

    List<GraphEntity> findByNovelId(UUID novelId);

    Optional<GraphEntity> findByNovelIdAndName(UUID novelId, String name);

    void deleteByNovelId(UUID novelId);

    long countByNovelId(UUID novelId);
}

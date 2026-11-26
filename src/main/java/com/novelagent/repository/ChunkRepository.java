package com.novelagent.repository;

import com.novelagent.entity.Chunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ChunkRepository extends JpaRepository<Chunk, UUID> {

    List<Chunk> findByNovelIdOrderByChunkIndexAsc(UUID novelId);

    List<Chunk> findByChapterIdOrderByChunkIndexAsc(UUID chapterId);

    @Modifying
    void deleteByNovelId(UUID novelId);

    @Modifying
    void deleteByChapterId(UUID chapterId);

    long countByNovelId(UUID novelId);
}

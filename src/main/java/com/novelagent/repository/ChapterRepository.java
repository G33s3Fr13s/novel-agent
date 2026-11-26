package com.novelagent.repository;

import com.novelagent.entity.Chapter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChapterRepository extends JpaRepository<Chapter, UUID> {

    List<Chapter> findByNovelIdOrderByChapterNumAsc(UUID novelId);

    Optional<Chapter> findByNovelIdAndChapterNum(UUID novelId, int chapterNum);

    boolean existsByNovelIdAndChapterNum(UUID novelId, int chapterNum);

    long countByNovelId(UUID novelId);
}

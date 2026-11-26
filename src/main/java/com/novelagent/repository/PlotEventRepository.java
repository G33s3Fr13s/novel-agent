package com.novelagent.repository;

import com.novelagent.entity.PlotEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PlotEventRepository extends JpaRepository<PlotEvent, UUID> {

    List<PlotEvent> findByNovelIdOrderByEventIndexAsc(UUID novelId);

    long countByNovelId(UUID novelId);

    void deleteByNovelId(UUID novelId);

    void deleteByChapterId(UUID chapterId);
}

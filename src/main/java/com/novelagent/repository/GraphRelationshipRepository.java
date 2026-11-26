package com.novelagent.repository;

import com.novelagent.entity.GraphRelationship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface GraphRelationshipRepository extends JpaRepository<GraphRelationship, UUID> {

    List<GraphRelationship> findBySourceIdOrTargetId(UUID entityId, UUID entityId2);

    void deleteBySourceIdOrTargetId(UUID entityId, UUID entityId2);

    @Query("SELECT r FROM GraphRelationship r WHERE r.source.novel.id = :novelId")
    List<GraphRelationship> findByNovelId(@Param("novelId") UUID novelId);

    @Modifying
    @Query("DELETE FROM GraphRelationship r WHERE r.source.novel.id = :novelId")
    void deleteByNovelId(@Param("novelId") UUID novelId);
}

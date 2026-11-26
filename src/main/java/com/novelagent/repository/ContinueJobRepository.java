package com.novelagent.repository;

import com.novelagent.entity.ContinueJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ContinueJobRepository extends JpaRepository<ContinueJob, UUID> {

    List<ContinueJob> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Page<ContinueJob> findByUserId(UUID userId, Pageable pageable);

    Optional<ContinueJob> findTopByNovelIdAndUserIdAndStatusInOrderByCreatedAtDesc(
            UUID novelId, UUID userId, List<String> statuses);
}

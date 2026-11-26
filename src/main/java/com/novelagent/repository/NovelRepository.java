package com.novelagent.repository;

import com.novelagent.entity.Novel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NovelRepository extends JpaRepository<Novel, UUID> {

    List<Novel> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    Optional<Novel> findByIdAndUserId(UUID id, UUID userId);
}

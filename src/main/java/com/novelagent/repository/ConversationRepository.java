package com.novelagent.repository;

import com.novelagent.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    List<Conversation> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    List<Conversation> findByUserIdAndTitleContainingIgnoreCaseOrderByUpdatedAtDesc(UUID userId, String title);
}

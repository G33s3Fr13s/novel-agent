package com.novelagent.repository;

import com.novelagent.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {

    List<Message> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    @Query("SELECT m FROM Message m WHERE m.conversation.user.id = :userId AND m.content LIKE %:keyword% ORDER BY m.createdAt DESC")
    List<Message> searchByContent(@Param("userId") UUID userId, @Param("keyword") String keyword);
}

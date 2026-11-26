package com.novelagent.repository;

import com.novelagent.entity.EvalResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface EvalResultRepository extends JpaRepository<EvalResult, UUID> {

    @Query("SELECT e FROM EvalResult e WHERE e.createdAt >= :since ORDER BY e.createdAt DESC")
    List<EvalResult> findRecent(@Param("since") Instant since);
}

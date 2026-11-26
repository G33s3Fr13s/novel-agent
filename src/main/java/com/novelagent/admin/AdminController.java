package com.novelagent.admin;

import com.novelagent.dto.ApiResponse;
import com.novelagent.entity.User;
import com.novelagent.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;

    /** 仪表盘总览 */
    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<Map<String, Object>>> dashboard() {
        requireAdmin();
        Map<String, Object> stats = new LinkedHashMap<>();

        // 用户数
        stats.put("totalUsers", jdbc.queryForObject("SELECT count(*) FROM users", Integer.class));
        stats.put("totalNovels", jdbc.queryForObject("SELECT count(*) FROM novels", Integer.class));
        stats.put("totalChapters", jdbc.queryForObject("SELECT count(*) FROM chapters", Integer.class));
        stats.put("totalChunks", jdbc.queryForObject("SELECT count(*) FROM chunks", Integer.class));
        stats.put("totalEntities", jdbc.queryForObject("SELECT count(*) FROM graph_entities", Integer.class));
        stats.put("totalRelations", jdbc.queryForObject("SELECT count(*) FROM graph_relationships", Integer.class));

        // 评测统计
        Map<String, Object> latest = jdbc.queryForMap(
                "SELECT context_precision, context_recall, graphrag_gain, created_at FROM eval_results ORDER BY created_at DESC LIMIT 1");
        stats.put("latestEval", latest);

        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    /** 评测历史趋势 */
    @GetMapping("/eval-trend")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> evalTrend() {
        requireAdmin();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT context_precision, context_recall, vector_recall, graphrag_gain, total_questions, created_at FROM eval_results ORDER BY created_at DESC LIMIT 30");
        return ResponseEntity.ok(ApiResponse.success(rows));
    }

    private void requireAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserDetails)) throw new RuntimeException("未登录");
        User user = userRepository.findByUsername(((UserDetails) auth.getPrincipal()).getUsername())
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        if (!"ADMIN".equals(user.getRole())) throw new RuntimeException("需要管理员权限");
    }
}

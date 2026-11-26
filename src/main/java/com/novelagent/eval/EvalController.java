package com.novelagent.eval;

import com.novelagent.dto.ApiResponse;
import com.novelagent.entity.EvalResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/eval")
@RequiredArgsConstructor
public class EvalController {

    private final EvalService evalService;

    /** POST/GET /api/eval/run?testset=golden_testset_shuihu — 跑一次评测 */
    @RequestMapping(value = "/run", method = {RequestMethod.POST, RequestMethod.GET})
    public ResponseEntity<ApiResponse<EvalResult>> run(
            @RequestParam(value = "testset", required = false) String testset) {
        String path = testset != null ? "eval/" + testset + ".json" : null;
        return ResponseEntity.ok(ApiResponse.success(evalService.run(path)));
    }

    /** GET /api/eval/results?days=30 — 历史趋势 */
    @GetMapping("/results")
    public ResponseEntity<ApiResponse<List<EvalResult>>> results(@RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(ApiResponse.success(evalService.getResults(days)));
    }
}

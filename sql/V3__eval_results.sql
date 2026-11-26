-- ============================================================
-- V3: RAG 评测结果表
-- ============================================================

CREATE TABLE IF NOT EXISTS eval_results (
    id               UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    testset_name     VARCHAR(200) NOT NULL,
    novel_id         UUID         NOT NULL REFERENCES novels(id) ON DELETE CASCADE,
    total_questions  INT          NOT NULL,
    context_precision DOUBLE PRECISION,
    context_recall   DOUBLE PRECISION,
    vector_precision DOUBLE PRECISION,
    vector_recall    DOUBLE PRECISION,
    graphrag_gain    DOUBLE PRECISION,
    details          JSONB,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_eval_results_novel ON eval_results (novel_id, created_at DESC);

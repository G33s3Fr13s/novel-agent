-- ============================================================
-- V2: Add web search fields to continue_jobs
-- ============================================================

ALTER TABLE continue_jobs ADD COLUMN IF NOT EXISTS search_sites TEXT;
ALTER TABLE continue_jobs ADD COLUMN IF NOT EXISTS search_hint TEXT;

-- Add status column if not present (V1 schema didn't have it)
ALTER TABLE continue_jobs ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED';

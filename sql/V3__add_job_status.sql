-- Add status column to continue_jobs for tracking generation progress
ALTER TABLE continue_jobs ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED';

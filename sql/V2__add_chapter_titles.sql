-- Add chapter_titles column to continue_jobs (for preview display)
ALTER TABLE continue_jobs ADD COLUMN IF NOT EXISTS chapter_titles TEXT;

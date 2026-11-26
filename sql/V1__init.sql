-- ============================================================
-- Novel Agent — Database Initialization
-- PostgreSQL 16+ with pgvector extension
-- ============================================================

-- Enable extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "vector";       -- pgvector for embeddings
CREATE EXTENSION IF NOT EXISTS "pg_trgm";      -- trigram indexing for fuzzy text search

-- ============================================================
-- Users table
-- ============================================================
CREATE TABLE IF NOT EXISTS users (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    username    VARCHAR(50)  NOT NULL,
    email       VARCHAR(255) NOT NULL,
    password    VARCHAR(255) NOT NULL,
    role        VARCHAR(20)  NOT NULL DEFAULT 'USER',
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT uq_users_email    UNIQUE (email)
);

CREATE INDEX IF NOT EXISTS idx_users_username ON users (username);
CREATE INDEX IF NOT EXISTS idx_users_email    ON users (email);

-- ============================================================
-- Refresh tokens table (server-side rotation tracking)
-- ============================================================
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(128) NOT NULL,           -- SHA-256 hash of the refresh token
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_hash    ON refresh_tokens (token_hash);

-- ============================================================
-- Conversations table
-- ============================================================
CREATE TABLE IF NOT EXISTS conversations (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title       VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_conversations_user_id ON conversations (user_id);

-- ============================================================
-- Messages table
-- ============================================================
CREATE TABLE IF NOT EXISTS messages (
    id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    conversation_id  UUID         NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role             VARCHAR(20)  NOT NULL CHECK (role IN ('user', 'assistant', 'system')),
    content          TEXT         NOT NULL,
    token_count      INTEGER,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_messages_conversation_id ON messages (conversation_id);

-- ============================================================
-- Novels table
-- ============================================================
CREATE TABLE IF NOT EXISTS novels (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title       VARCHAR(500) NOT NULL,
    author      VARCHAR(255),
    description TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_novels_user_id ON novels (user_id);

-- ============================================================
-- Chapters table — one novel has many chapters
-- ============================================================
CREATE TABLE IF NOT EXISTS chapters (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    novel_id        UUID         NOT NULL REFERENCES novels(id) ON DELETE CASCADE,
    chapter_num     INTEGER      NOT NULL,
    title           VARCHAR(500),
    content         TEXT         NOT NULL,
    char_count      INTEGER      NOT NULL DEFAULT 0,   -- 中文字数
    paragraph_count INTEGER      NOT NULL DEFAULT 0,   -- 段落数（给语义分块用）
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_chapters_novel_num UNIQUE (novel_id, chapter_num)
);

CREATE INDEX IF NOT EXISTS idx_chapters_novel_id ON chapters (novel_id);

-- ============================================================
-- ============================================================
-- Chunks table — semantic chunks (Module 5-6)
-- ============================================================
CREATE TABLE IF NOT EXISTS chunks (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    chapter_id  UUID         NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
    novel_id    UUID         NOT NULL REFERENCES novels(id) ON DELETE CASCADE,
    chunk_index INTEGER      NOT NULL,                    -- 在该小说所有 chunk 中的序号
    content     TEXT         NOT NULL,                    -- 合并后的段落文本
    para_start  INTEGER      NOT NULL,                    -- 起始段落号（在所属章节中）
    para_end    INTEGER      NOT NULL,                    -- 结束段落号
    embedding   vector(1024),                              -- pgvector: Qwen text-embedding-v4 = 1024 维
    char_count  INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_chunks_chapter_paras UNIQUE (chapter_id, para_start, para_end)
);

CREATE INDEX IF NOT EXISTS idx_chunks_chapter_id ON chunks (chapter_id);
CREATE INDEX IF NOT EXISTS idx_chunks_novel_id    ON chunks (novel_id);
-- hnsw 索引后续 Module 8 再建，那时才需要向量检索

-- ============================================================
-- ============================================================
-- Graph entities — 人物/地点/物品/概念 (Module 7)
-- ============================================================
CREATE TABLE IF NOT EXISTS graph_entities (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    novel_id    UUID         NOT NULL REFERENCES novels(id) ON DELETE CASCADE,
    name        VARCHAR(255) NOT NULL,
    type        VARCHAR(50)  NOT NULL CHECK (type IN ('PERSON', 'LOCATION', 'ITEM', 'CONCEPT')),
    aliases     TEXT[]       NOT NULL DEFAULT '{}',          -- 别名数组: {"叶林","林哥"}
    description TEXT,
    metadata    JSONB        NOT NULL DEFAULT '{}',          -- 任意附加属性
    embedding   vector(1024),                                -- 实体名+描述的语义向量
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_graph_entities_novel_name UNIQUE (novel_id, name)
);

CREATE INDEX IF NOT EXISTS idx_graph_entities_novel_id ON graph_entities (novel_id);

-- ============================================================
-- Graph relationships — 实体间关系 (Module 7)
-- ============================================================
CREATE TABLE IF NOT EXISTS graph_relationships (
    id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    source_entity_id UUID         NOT NULL REFERENCES graph_entities(id) ON DELETE CASCADE,
    target_entity_id UUID         NOT NULL REFERENCES graph_entities(id) ON DELETE CASCADE,
    relation_type    VARCHAR(100) NOT NULL,                  -- 师徒/敌对/拥有/位于...
    description      TEXT,
    weight           FLOAT        NOT NULL DEFAULT 1.0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_graph_rels UNIQUE (source_entity_id, target_entity_id, relation_type)
);

CREATE INDEX IF NOT EXISTS idx_graph_rels_source ON graph_relationships (source_entity_id);
CREATE INDEX IF NOT EXISTS idx_graph_rels_target ON graph_relationships (target_entity_id);

-- ============================================================
-- Plot events — 剧情事件 (Module 9)
-- ============================================================
CREATE TABLE IF NOT EXISTS plot_events (
    id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    novel_id         UUID         NOT NULL REFERENCES novels(id) ON DELETE CASCADE,
    event_index      INTEGER      NOT NULL,
    chapter_id       UUID         REFERENCES chapters(id) ON DELETE SET NULL,
    summary          TEXT         NOT NULL,
    key_entities     text[],
    event_type       VARCHAR(30)  NOT NULL DEFAULT 'PLOT',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_plot_events_novel_idx UNIQUE (novel_id, event_index)
);

-- ============================================================
-- 续写作业记录
-- ============================================================
CREATE TABLE IF NOT EXISTS continue_jobs (
    id              UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    novel_id        UUID         NOT NULL REFERENCES novels(id) ON DELETE CASCADE,
    novel_title     VARCHAR(500) NOT NULL,
    chapter_ids     TEXT,
    chapter_titles  TEXT,
    chapter_count   INT          NOT NULL DEFAULT 1,
    foreword        TEXT,
    afterword       TEXT,
    prompt          TEXT,
    result          TEXT,
    result_length   INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_continue_jobs_user ON continue_jobs (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_plot_events_novel_id ON plot_events (novel_id);

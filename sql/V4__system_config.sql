-- 系统配置（引导向导填写的 API Key 等）
CREATE TABLE IF NOT EXISTS system_config (
    key   VARCHAR(100) PRIMARY KEY,
    value TEXT NOT NULL
);

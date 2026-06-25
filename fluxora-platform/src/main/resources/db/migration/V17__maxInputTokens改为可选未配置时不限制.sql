-- V17：max_input_tokens / max_output_tokens / max_cache_write_tokens / max_cache_read_tokens
-- 改为可选字段，未配置（NULL）时不限制。

-- 1. 放宽检查约束，允许所有 Token 上限字段为 NULL
ALTER TABLE tenant_model DROP CONSTRAINT IF EXISTS chk_tenant_model_token_limits;
ALTER TABLE tenant_model ADD CONSTRAINT chk_tenant_model_token_limits CHECK (
    (max_input_tokens IS NULL OR max_input_tokens > 0)
    AND (max_output_tokens IS NULL OR max_output_tokens > 0)
    AND default_output_tokens > 0
    AND (max_output_tokens IS NULL OR default_output_tokens <= max_output_tokens)
    AND (max_cache_write_tokens IS NULL OR max_cache_write_tokens >= 0)
    AND (max_cache_read_tokens IS NULL OR max_cache_read_tokens >= 0));

-- 2. 去掉 NOT NULL 与硬编码默认值，NULL 语义为"不限制"
ALTER TABLE tenant_model ALTER COLUMN max_input_tokens DROP NOT NULL;
ALTER TABLE tenant_model ALTER COLUMN max_input_tokens SET DEFAULT NULL;
ALTER TABLE tenant_model ALTER COLUMN max_output_tokens DROP NOT NULL;
ALTER TABLE tenant_model ALTER COLUMN max_output_tokens SET DEFAULT NULL;
ALTER TABLE tenant_model ALTER COLUMN max_cache_write_tokens DROP NOT NULL;
ALTER TABLE tenant_model ALTER COLUMN max_cache_write_tokens SET DEFAULT NULL;
ALTER TABLE tenant_model ALTER COLUMN max_cache_read_tokens DROP NOT NULL;
ALTER TABLE tenant_model ALTER COLUMN max_cache_read_tokens SET DEFAULT NULL;

COMMENT ON COLUMN tenant_model.max_input_tokens IS '单请求允许的最大输入 Token 保守上限；NULL 表示不限制';
COMMENT ON COLUMN tenant_model.max_output_tokens IS '客户端可声明的最大输出 Token；NULL 表示不限制';
COMMENT ON COLUMN tenant_model.max_cache_write_tokens IS '缓存写入 Token 上限；NULL 表示不限制';
COMMENT ON COLUMN tenant_model.max_cache_read_tokens IS '缓存读取 Token 上限；NULL 表示不限制';

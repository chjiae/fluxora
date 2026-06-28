-- V18：彻底删除租户模型 Token 长度上限字段。
-- 自 V18 起，Fluxora 不再在 TenantModel 层保存或校验：
--   输入 Token 上限、输出 Token 上限、缓存写入 Token 上限、缓存读取 Token 上限。
-- 真实上游的上下文、输入、输出限制由上游最终裁决。
-- 用户请求中的协议原生 max_tokens 等参数仍按现有协议逻辑透传。
-- 模型能力字段、四类价格、四类 usage、结算和对账不受本次删除影响。

-- 1. 删除 V17 放宽版 CHECK 约束
ALTER TABLE tenant_model DROP CONSTRAINT IF EXISTS chk_tenant_model_token_limits;

-- 2. 删除四个 Token 上限列
ALTER TABLE tenant_model DROP COLUMN IF EXISTS max_input_tokens;
ALTER TABLE tenant_model DROP COLUMN IF EXISTS max_output_tokens;
ALTER TABLE tenant_model DROP COLUMN IF EXISTS max_cache_write_tokens;
ALTER TABLE tenant_model DROP COLUMN IF EXISTS max_cache_read_tokens;

-- 3. 仅保留 default_output_tokens 非空与合法性校验
--    default_output_tokens 是客户端未声明 max_tokens 时的默认填充值，不是上限
ALTER TABLE tenant_model ADD CONSTRAINT chk_tenant_model_default_output CHECK (default_output_tokens > 0);

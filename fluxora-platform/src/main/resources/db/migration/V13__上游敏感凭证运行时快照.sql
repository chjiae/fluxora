-- ============================================================================
-- Fluxora V13：上游敏感凭证独立运行时快照。
--
-- 本迁移为 ProviderCredential 增加稳定版本与认证类型。Platform 使用这些字段生成
-- UPSTREAM_CREDENTIAL Redis 敏感快照，Gateway 只读取运行时重加密密文；普通
-- TENANT_MODEL_ROUTE 快照只保留凭证引用、状态和版本，绝不包含密文、IV 或明文。
-- ============================================================================

ALTER TABLE provider_credential
    ADD COLUMN IF NOT EXISTS auth_type VARCHAR(32) NOT NULL DEFAULT 'BEARER';
ALTER TABLE provider_credential
    ADD COLUMN IF NOT EXISTS credential_version BIGINT NOT NULL DEFAULT 1;

ALTER TABLE provider_credential
    DROP CONSTRAINT IF EXISTS chk_provider_credential_auth_type;
ALTER TABLE provider_credential
    ADD CONSTRAINT chk_provider_credential_auth_type
    CHECK (auth_type IN ('BEARER', 'X_API_KEY', 'NONE'));
ALTER TABLE provider_credential
    ADD CONSTRAINT chk_provider_credential_version
    CHECK (credential_version >= 1);

COMMENT ON COLUMN provider_credential.auth_type IS '上游认证注入方式：BEARER 写入 Authorization，X_API_KEY 写入 x-api-key，NONE 仅用于明确允许的无认证本地或测试上游';
COMMENT ON COLUMN provider_credential.credential_version IS '凭证安全版本：密文、认证类型或启用状态变化时单调递增；路由引用与敏感运行时快照必须匹配该版本';
COMMENT ON COLUMN runtime_snapshot_version.scope_type IS 'Scope 类型：AUTH_API_KEY、AUTH_USER、AUTH_TENANT、TENANT_MODEL_ROUTE 或 UPSTREAM_CREDENTIAL；后者为 Gateway 专用敏感运行时密文快照';

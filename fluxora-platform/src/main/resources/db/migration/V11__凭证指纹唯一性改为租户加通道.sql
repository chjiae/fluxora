-- Fluxora V11：将凭证去重指纹的唯一索引从 (tenant_id, fingerprint) 改为 (tenant_id, provider_channel_id, fingerprint)，
-- 允许同一租户的不同通道使用相同 API Key。
DROP INDEX IF EXISTS uk_provider_credential_tenant_fingerprint_active;
CREATE UNIQUE INDEX uk_provider_credential_channel_fingerprint_active
    ON provider_credential (tenant_id, provider_channel_id, credential_fingerprint)
    WHERE deleted_at IS NULL;
COMMENT ON COLUMN provider_credential.credential_fingerprint IS 'HMAC-SHA-256 去重指纹；仅用于当前租户当前通道未删除凭证的重复判断，不对外返回';

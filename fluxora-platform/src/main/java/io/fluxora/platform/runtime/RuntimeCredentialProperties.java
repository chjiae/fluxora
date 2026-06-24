package io.fluxora.platform.runtime;

import jakarta.annotation.PostConstruct;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Platform 写入 Redis 敏感凭证快照使用的独立运行时密钥。
 * 该密钥与控制面数据库主密钥分离，Gateway 只读取同名环境变量，不读取 PostgreSQL 配置。
 */
@ConfigurationProperties("fluxora.runtime.credential")
public class RuntimeCredentialProperties {
    private String encryptionKey;

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    public byte[] encryptionKeyBytes() {
        try {
            byte[] decoded = Base64.getDecoder().decode(encryptionKey);
            if (decoded.length != 32) {
                throw new IllegalStateException("运行时凭证安全配置无效");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("运行时凭证安全配置无效");
        }
    }

    @PostConstruct
    void validate() {
        encryptionKeyBytes();
    }
}

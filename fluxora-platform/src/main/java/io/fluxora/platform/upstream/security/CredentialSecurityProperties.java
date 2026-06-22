package io.fluxora.platform.upstream.security;

import java.util.Base64;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 上游凭证的内部安全配置。
 * 主密钥与去重密钥只允许来自安全配置，配置对象不提供任何会把原值写入日志的 API。
 */
@Validated
@ConfigurationProperties("fluxora.security.credential")
public class CredentialSecurityProperties {

    private String masterKey;
    private String fingerprintKey;
    private int importMaxCount = 1000;

    public String getMasterKey() { return masterKey; }
    public void setMasterKey(String masterKey) { this.masterKey = masterKey; }
    public String getFingerprintKey() { return fingerprintKey; }
    public void setFingerprintKey(String fingerprintKey) { this.fingerprintKey = fingerprintKey; }
    public int getImportMaxCount() { return importMaxCount; }
    public void setImportMaxCount(int importMaxCount) { this.importMaxCount = importMaxCount; }

    /** 返回 AES-256 所需的 32 字节密钥；格式错误仅返回通用异常，不泄露配置值。 */
    public byte[] masterKeyBytes() { return decodeKey(masterKey); }
    /** 返回 HMAC-SHA-256 所需的独立 32 字节去重密钥。 */
    public byte[] fingerprintKeyBytes() { return decodeKey(fingerprintKey); }

    /**
     * 服务启动即校验密钥和导入上限，防止错误配置在首次写凭证时才暴露。
     * 异常故意不拼接配置原文，避免环境变量被诊断信息泄露。
     */
    @PostConstruct
    void validate() {
        masterKeyBytes();
        fingerprintKeyBytes();
        if (importMaxCount < 1 || importMaxCount > 5000) {
            throw new IllegalStateException("凭证导入数量限制配置无效");
        }
    }

    private byte[] decodeKey(String configuredValue) {
        try {
            byte[] decoded = Base64.getDecoder().decode(configuredValue);
            if (decoded.length != 32) {
                throw new IllegalStateException("凭证安全配置无效");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("凭证安全配置无效");
        }
    }
}

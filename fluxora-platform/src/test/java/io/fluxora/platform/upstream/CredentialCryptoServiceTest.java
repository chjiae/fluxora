package io.fluxora.platform.upstream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.fluxora.platform.upstream.security.CredentialCryptoService;
import io.fluxora.platform.upstream.security.CredentialSecurityProperties;
import io.fluxora.platform.upstream.security.EncryptedCredential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 凭证加密服务单元测试。
 * 覆盖 AES-256-GCM 可逆加解密、HMAC 指纹规范化、脱敏与版本校验。
 */
class CredentialCryptoServiceTest {

    private CredentialCryptoService crypto;

    /** 固定 32 字节测试密钥，仅用于单元测试，不来自生产配置。 */
    private static final String MASTER = java.util.Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    private static final String FINGERPRINT = java.util.Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes());

    @BeforeEach
    void setUp() {
        CredentialSecurityProperties properties = new CredentialSecurityProperties();
        properties.setMasterKey(MASTER);
        properties.setFingerprintKey(FINGERPRINT);
        crypto = new CredentialCryptoService(properties);
    }

    @Test
    void encryptDecryptShouldRoundtrip() {
        EncryptedCredential encrypted = crypto.encrypt("sk-AbC9");
        assertThat(crypto.decrypt(encrypted)).isEqualTo("sk-AbC9");
    }

    @Test
    void encryptShouldProduceDifferentCiphertextForSamePlaintext() {
        // AES-GCM 随机 IV，相同明文密文不同；重复识别必须依赖指纹
        EncryptedCredential a = crypto.encrypt("sk-same");
        EncryptedCredential b = crypto.encrypt("sk-same");
        assertThat(a.ciphertext()).isNotEqualTo(b.ciphertext());
        assertThat(a.initializationVector()).isNotEqualTo(b.initializationVector());
    }

    @Test
    void fingerprintShouldIgnoreSurroundingWhitespace() {
        assertThat(crypto.fingerprint(" sk-AbC9\n")).isEqualTo(crypto.fingerprint("sk-AbC9"));
    }

    @Test
    void fingerprintShouldPreserveCase() {
        // 凭证大小写敏感，不得转小写或修改中间字符
        assertThat(crypto.fingerprint("sk-AbC9")).isNotEqualTo(crypto.fingerprint("sk-abc9"));
    }

    @Test
    void maskShouldNeverContainFullPlaintext() {
        String masked = crypto.mask("sk-AbCdefGHIJklmn9X");
        assertThat(masked).doesNotContain("AbCdefGHIJklmn");
        assertThat(masked).startsWith("sk-A");
        assertThat(masked).endsWith("9X");
    }

    @Test
    void maskShortCredentialShouldHideMiddle() {
        assertThat(crypto.mask("short")).doesNotContain("hort");
    }

    @Test
    void decryptShouldRejectUnsupportedVersion() {
        EncryptedCredential encrypted = crypto.encrypt("sk-x");
        EncryptedCredential bad = new EncryptedCredential(encrypted.ciphertext(), encrypted.initializationVector(), "UNKNOWN");
        assertThatThrownBy(() -> crypto.decrypt(bad)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fingerprintShouldRejectBlankPlaintext() {
        // 空白明文在服务层 requirePlaintext 与 fingerprint 规范化处统一拒绝
        assertThatThrownBy(() -> crypto.fingerprint("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> crypto.mask("  ")).isInstanceOf(IllegalArgumentException.class);
    }
}

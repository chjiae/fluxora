package io.fluxora.platform.upstream.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

/**
 * 上游凭证的可逆加密与不可逆去重指纹服务。
 * AES-GCM 每次加密都生成新的随机 IV，因此同一明文不能比较密文；重复识别必须使用独立 HMAC 指纹。
 */
@Service
public class CredentialCryptoService {

    private static final String ENCRYPTION_VERSION = "AES256_GCM_V1";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;

    private final CredentialSecurityProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public CredentialCryptoService(CredentialSecurityProperties properties) {
        this.properties = properties;
    }

    public EncryptedCredential encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(properties.masterKeyBytes(), "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new EncryptedCredential(Base64.getEncoder().encodeToString(encrypted),
                    Base64.getEncoder().encodeToString(iv), ENCRYPTION_VERSION);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("凭证加密处理失败");
        }
    }

    /** 仅供内部安全测试和未来网关内部调用使用，任何 Controller 均不得调用后回显结果。 */
    public String decrypt(EncryptedCredential encryptedCredential) {
        try {
            if (!ENCRYPTION_VERSION.equals(encryptedCredential.encryptionVersion())) {
                throw new IllegalStateException("凭证加密版本不受支持");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(properties.masterKeyBytes(), "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS,
                            Base64.getDecoder().decode(encryptedCredential.initializationVector())));
            return new String(cipher.doFinal(Base64.getDecoder().decode(encryptedCredential.ciphertext())), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("凭证解密处理失败");
        }
    }

    public String fingerprint(String plaintext) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.fingerprintKeyBytes(), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(normalize(plaintext).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("凭证指纹处理失败");
        }
    }

    /** 凭证通常大小写敏感，只清理首尾空白，禁止 lower-case 或修改中间字符。 */
    public String normalize(String plaintext) {
        if (plaintext == null || plaintext.trim().isEmpty()) {
            throw new IllegalArgumentException("请输入上游访问凭证");
        }
        return plaintext.trim();
    }

    /** 列表、详情和导入结果使用的安全展示值，永不包含完整明文。 */
    public String mask(String plaintext) {
        String normalized = normalize(plaintext);
        if (normalized.length() <= 6) {
            return normalized.substring(0, 1) + "***";
        }
        return normalized.substring(0, Math.min(4, normalized.length() - 2)) + "***"
                + normalized.substring(normalized.length() - 2);
    }
}

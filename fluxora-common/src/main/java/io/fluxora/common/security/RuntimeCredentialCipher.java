package io.fluxora.common.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 运行时凭证专用 AES-256-GCM 编解码器。
 * 控制面与 Gateway 共享算法契约，但各自只从本进程安全配置读取运行时密钥，
 * 避免数据库主密钥进入 Redis、Gateway 或公共日志。
 */
public final class RuntimeCredentialCipher {
    public static final String VERSION = "RUNTIME_AES256_GCM_V1";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;

    private RuntimeCredentialCipher() {
    }

    public static EncryptedPayload encrypt(byte[] key, String plaintext, String aad) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(requireKey(key), "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new EncryptedPayload(Base64.getEncoder().encodeToString(ciphertext),
                    Base64.getEncoder().encodeToString(iv), VERSION);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("运行时凭证加密处理失败");
        }
    }

    public static String decrypt(byte[] key, EncryptedPayload payload, String aad) {
        try {
            if (payload == null || !VERSION.equals(payload.encryptionVersion())) {
                throw new IllegalStateException("运行时凭证加密版本不受支持");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(requireKey(key), "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.getDecoder().decode(payload.initializationVector())));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(payload.ciphertext())), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("运行时凭证解密处理失败");
        }
    }

    private static byte[] requireKey(byte[] key) {
        if (key == null || key.length != 32) {
            throw new IllegalStateException("运行时凭证安全配置无效");
        }
        return key;
    }

    /** Redis 敏感快照中的密文材料；该对象不包含明文或密钥。 */
    public record EncryptedPayload(String ciphertext, String initializationVector, String encryptionVersion) {
    }
}

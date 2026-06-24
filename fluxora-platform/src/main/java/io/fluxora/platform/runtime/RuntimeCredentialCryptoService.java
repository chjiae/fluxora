package io.fluxora.platform.runtime;

import io.fluxora.common.security.RuntimeCredentialCipher;
import io.fluxora.platform.upstream.security.CredentialCryptoService;
import io.fluxora.platform.upstream.security.EncryptedCredential;
import org.springframework.stereotype.Service;

/**
 * 控制面凭证重加密边界。
 * 原数据库密文仅在 Platform 内部短暂解密，再立刻转换为 Gateway 运行时密文；
 * Gateway 不需要也不能取得控制面主密钥。
 */
@Service
public class RuntimeCredentialCryptoService {
    private final CredentialCryptoService databaseCrypto;
    private final RuntimeCredentialProperties runtimeProperties;

    public RuntimeCredentialCryptoService(CredentialCryptoService databaseCrypto,
                                          RuntimeCredentialProperties runtimeProperties) {
        this.databaseCrypto = databaseCrypto;
        this.runtimeProperties = runtimeProperties;
    }

    public RuntimeCredentialCipher.EncryptedPayload reencrypt(String ciphertext, String initializationVector,
                                                                String encryptionVersion, long tenantId,
                                                                long credentialId, long credentialVersion) {
        String plaintext = databaseCrypto.decrypt(new EncryptedCredential(ciphertext, initializationVector, encryptionVersion));
        try {
            return RuntimeCredentialCipher.encrypt(runtimeProperties.encryptionKeyBytes(), plaintext,
                    aad(tenantId, credentialId, credentialVersion));
        } finally {
            plaintext = null;
        }
    }

    public static String aad(long tenantId, long credentialId, long credentialVersion) {
        return tenantId + ":" + credentialId + ":" + credentialVersion;
    }
}

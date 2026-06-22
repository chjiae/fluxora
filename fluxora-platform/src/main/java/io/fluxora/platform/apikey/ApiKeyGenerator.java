package io.fluxora.platform.apikey;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * API Key 随机生成器。
 *
 * 输出形如 {@code flx_<8 字符可见前缀>_<32 字符密钥段>}，总 45 字符。
 *   - 字符集：URL-safe Base64 子集（{@code A-Z a-z 0-9 _ -}），共 64 个字符。
 *   - 8 字符前缀提供 48 bit 命名空间；密钥段 32 字符提供 192 bit 熵，
 *     远高于现代凭证最小推荐（128 bit）。
 *   - 使用 {@link SecureRandom} 默认实例（首选 {@code NativePRNG} / Windows {@code SHA1PRNG}），
 *     不使用 {@code SecureRandom.getInstanceStrong()} 避免 Linux 上首次启动阻塞。
 *
 * 该类只生成；不参与哈希与持久化（分层清晰，便于测试）。
 */
@Component
public class ApiKeyGenerator {

    static final String PREFIX_HEAD = "flx_";
    private static final int PREFIX_BODY_LEN = 8;
    private static final int SECRET_LEN = 32;
    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-".toCharArray();

    private final SecureRandom random = new SecureRandom();

    public GeneratedKey generate() {
        String prefix = PREFIX_HEAD + randomString(PREFIX_BODY_LEN);
        String secret = randomString(SECRET_LEN);
        String plaintext = prefix + "_" + secret;
        return new GeneratedKey(plaintext, prefix, secret);
    }

    private String randomString(int len) {
        char[] buf = new char[len];
        for (int i = 0; i < len; i++) {
            buf[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(buf);
    }

    /**
     * 生成结果：plaintext 仅在创建响应中返回一次；prefix 入库且用于索引；
     * secretPart 立刻交给 {@link ApiKeyHashingService#hash(String)} 后即丢弃。
     */
    public record GeneratedKey(String plaintext, String prefix, String secretPart) {}
}

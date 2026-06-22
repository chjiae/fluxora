package io.fluxora.platform.card;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * 卡密生成器。
 *
 * 格式：FLX-XXXX-XXXX-XXXX-XXXX-XXXX（5 段 × 4 字符 = 20 + 4 分隔符，共 24 字符）
 * 字符集：Crockford Base32 子集（排除 0/O、1/I/l、U），共 30 个字符
 * 熵：20 × log₂(30) ≈ 98 bit，远高于 64 bit 最低安全阈值
 *
 * 前缀（公开标识）：前 8 字符 FLX-XXXX，用于列表脱敏展示
 * 完整明文：仅在批次创建响应中返回一次，后续任何接口均不返回
 */
@Component
public class CardCodeGenerator {

    private static final String PREFIX_HEAD = "FLX";
    // 排除容易混淆的 0/O/1/I/L/U，剩余 30 个字符
    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789".toCharArray();
    private static final int GROUPS = 5;
    private static final int GROUP_LEN = 4;

    private final SecureRandom random = new SecureRandom();

    public GeneratedCard generate() {
        String[] groups = new String[GROUPS];
        StringBuilder sb = new StringBuilder(PREFIX_HEAD);
        for (int i = 0; i < GROUPS; i++) {
            groups[i] = randomString(GROUP_LEN);
            sb.append('-').append(groups[i]);
        }
        String plaintext = sb.toString();
        String prefix = PREFIX_HEAD + "-" + groups[0];
        return new GeneratedCard(plaintext, prefix);
    }

    private String randomString(int len) {
        char[] buf = new char[len];
        for (int i = 0; i < len; i++) buf[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        return new String(buf);
    }

    public record GeneratedCard(String plaintext, String prefix) {}
}
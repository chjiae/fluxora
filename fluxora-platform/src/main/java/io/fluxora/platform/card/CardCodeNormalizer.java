package io.fluxora.platform.card;

import java.util.Set;

/**
 * 卡密输入规范化器。
 *
 * 用户输入时允许大小写、空格、连字符容错；后端统一规范化后再 HMAC 比对。
 * 规范化后严格校验格式（长度 23 字符、前缀 FLX、全部字符在 Crockford Base32 子集内），
 * 不通过则返回 null，调用方抛 CARD_CODE_INVALID。
 */
public final class CardCodeNormalizer {

    private static final String PREFIX = "FLX";
    private static final int STRIPPED_LEN = 23;   // FLX + 20 字符
    private static final int GROUPS = 5;
    private static final int GROUP_LEN = 4;

    private static final Set<Character> ALLOWED =
            Set.of('A','B','C','D','E','F','G','H','J','K','M','N','P','Q','R','S','T','V','W','X','Y','Z',
                   '2','3','4','5','6','7','8','9');

    private CardCodeNormalizer() {}

    /**
     * 规范化用户输入：去空格/连字符 → 统一大写 → 校验长度/前缀/字符集 →
     * 重新分段为 FLX-XXXX-XXXX-XXXX-XXXX-XXXX。
     *
     * @return 规范化后的 24 字符标准格式；输入不合法时返回 null
     */
    public static String normalize(String userInput) {
        if (userInput == null || userInput.isBlank()) return null;
        String stripped = userInput.replaceAll("[\\s\\-]+", "").toUpperCase();
        if (stripped.length() != STRIPPED_LEN || !stripped.startsWith(PREFIX)) return null;
        String body = stripped.substring(PREFIX.length());
        for (char c : body.toCharArray()) {
            if (!ALLOWED.contains(c)) return null;
        }
        StringBuilder sb = new StringBuilder(PREFIX);
        for (int i = 0; i < GROUPS; i++) {
            sb.append('-').append(body, i * GROUP_LEN, i * GROUP_LEN + GROUP_LEN);
        }
        return sb.toString();
    }
}
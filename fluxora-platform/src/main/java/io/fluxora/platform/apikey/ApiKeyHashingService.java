package io.fluxora.platform.apikey;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * API Key 哈希服务。
 *
 * 安全模型：
 *   - 完整 Key 形如 {@code flx_<8字符前缀>_<32字符密钥段>}。
 *   - 数据库仅保存 {@code key_prefix} 与 {@code lookup_hash}；明文绝不落库。
 *   - 哈希算法：HMAC-SHA256，密钥 = 服务器配置的 Lookup Secret（来自
 *     {@code fluxora.security.apikey.lookup-secret}），输入必须是完整 canonical API Key。
 *     该摘要可由 Gateway 在请求热路径计算一次，既不需要数据库查询也不需要慢密码哈希。
 *   - Lookup Secret 在内存中保留，绝不写入日志、堆栈、响应或前端任何位置。
 *
 * 该类是无状态的 Spring 单例；hash() / verify() 线程安全（每次调用创建新 {@link Mac}）。
 * verify() 在本轮 controller 路径中不被调用，预留给未来网关。
 */
@Component
public class ApiKeyHashingService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyHashingService.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] lookupSecret;

    public ApiKeyHashingService(@Value("${fluxora.security.apikey.lookup-secret}") String lookupSecret) {
        if (lookupSecret == null || lookupSecret.isEmpty()) {
            // 不允许空 Secret：那样 HMAC 退化为可预测的 SHA-256
            throw new IllegalStateException(
                    "fluxora.security.apikey.lookup-secret 未配置；请通过 APIKEY_LOOKUP_SECRET 环境变量提供");
        }
        this.lookupSecret = lookupSecret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 启动时校验 Lookup Secret 长度；过短只警告不阻塞，让本地开发可继续。
     * 生产部署应通过环境变量提供 32 字符以上的随机字符串。
     */
    @PostConstruct
    void warnIfPepperWeak() {
        if (lookupSecret.length < 32) {
            log.warn("API Key Lookup Secret 长度仅 {} 字节；生产环境建议至少 32 字节随机字符串", lookupSecret.length);
        }
    }

    /**
     * 计算完整 canonical API Key 的 HMAC-SHA256 hex 摘要（64 字符小写）。
     * 调用方必须在传入前完成格式校验与 canonical 化，避免相同 Key 出现多种查找表示。
     */
    public String lookupHash(String canonicalApiKey) {
        if (canonicalApiKey == null || canonicalApiKey.isEmpty()) {
            throw new IllegalArgumentException("canonicalApiKey 不能为空");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(lookupSecret, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(canonicalApiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            // HmacSHA256 是 JDK 强制要求的算法，正常 JVM 上不会触发
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }

    /**
     * 常量时间比较，避免时序侧信道。供控制面安全自检与未来轮换流程使用。
     * 任一参数为空一律返回 false。
     */
    public boolean verifyLookup(String canonicalApiKey, String storedLookupHash) {
        if (canonicalApiKey == null || storedLookupHash == null) return false;
        return constantTimeEquals(lookupHash(canonicalApiKey), storedLookupHash);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}

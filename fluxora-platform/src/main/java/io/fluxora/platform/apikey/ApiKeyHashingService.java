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
 *   - 数据库仅保存 {@code key_prefix} 与 {@code key_hash}；明文绝不落库。
 *   - 哈希算法：HMAC-SHA256，密钥 = 服务器配置的 pepper（来自
 *     {@code fluxora.security.apikey.pepper}）。HMAC 比纯 SHA-256 抗预计算彩虹表，
 *     比 BCrypt 快 1000+ 倍，适合未来网关高频校验。
 *   - Pepper 在内存中保留，绝不写入日志、堆栈、异常消息、响应或前端任何位置。
 *
 * 该类是无状态的 Spring 单例；hash() / verify() 线程安全（每次调用创建新 {@link Mac}）。
 * verify() 在本轮 controller 路径中不被调用，预留给未来网关。
 */
@Component
public class ApiKeyHashingService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyHashingService.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] pepper;

    public ApiKeyHashingService(@Value("${fluxora.security.apikey.pepper}") String pepper) {
        if (pepper == null || pepper.isEmpty()) {
            // 不允许空 pepper：那样 HMAC 退化为可预测的 SHA-256
            throw new IllegalStateException(
                    "fluxora.security.apikey.pepper 未配置；请通过 APIKEY_PEPPER 环境变量提供");
        }
        this.pepper = pepper.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 启动时校验 pepper 长度；过短只警告不阻塞，让本地开发可继续。
     * 生产部署应通过环境变量提供 32 字符以上的随机字符串。
     */
    @PostConstruct
    void warnIfPepperWeak() {
        if (pepper.length < 32) {
            log.warn("API Key pepper 长度仅 {} 字节；生产环境建议至少 32 字节随机字符串", pepper.length);
        }
    }

    /**
     * 计算 secretPart 的 HMAC-SHA256 hex 摘要（64 字符小写）。
     * 调用方必须确保入参 secretPart 不为空。
     */
    public String hash(String secretPart) {
        if (secretPart == null || secretPart.isEmpty()) {
            throw new IllegalArgumentException("secretPart 不能为空");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(pepper, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(secretPart.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            // HmacSHA256 是 JDK 强制要求的算法，正常 JVM 上不会触发
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }

    /**
     * 常量时间比较，避免时序侧信道。预留给未来网关。
     * 任一参数为空一律返回 false。
     */
    public boolean verify(String secretPart, String storedHash) {
        if (secretPart == null || storedHash == null) return false;
        return constantTimeEquals(hash(secretPart), storedHash);
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

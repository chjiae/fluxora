package io.fluxora.platform.card;

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
 * 卡密哈希服务，镜像 {@link io.fluxora.platform.apikey.ApiKeyHashingService}，
 * 使用独立 pepper（{@code fluxora.security.card.pepper}），提供防御纵深。
 */
@Component
public class CardHashingService {

    private static final Logger log = LoggerFactory.getLogger(CardHashingService.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final byte[] pepper;

    public CardHashingService(@Value("${fluxora.security.card.pepper}") String pepper) {
        if (pepper == null || pepper.isEmpty()) {
            throw new IllegalStateException("fluxora.security.card.pepper 未配置");
        }
        this.pepper = pepper.getBytes(StandardCharsets.UTF_8);
    }

    @PostConstruct
    void warnIfPepperWeak() {
        if (pepper.length < 32) {
            log.warn("卡密 Pepper 长度仅 {} 字节；生产环境建议至少 32 字节", pepper.length);
        }
    }

    public String hash(String normalizedCode) {
        if (normalizedCode == null || normalizedCode.isEmpty()) {
            throw new IllegalArgumentException("normalizedCode 不能为空");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(pepper, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(normalizedCode.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }
}
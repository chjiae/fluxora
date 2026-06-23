package io.fluxora.platform.model;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.platform.billing.CnyPrecisionPolicy;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.model.dto.TenantModelPriceView;
import io.fluxora.platform.model.mapper.TenantModelPriceMapper;
import io.fluxora.platform.upstream.security.UpstreamTenantGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户模型价格服务。
 * V10 后所有价格只属于 TenantModel；不存在平台默认价、价格继承或跨租户价格复用。
 * 改价新增版本而非覆盖；同一模型同时只有一个 expired_at IS NULL 的有效版本。
 * 不支持缓存的模型不得提交缓存读写价格；支持缓存的模型必须同时提交缓存读写价格。
 */
@Service
public class TenantModelPriceService {

    private final TenantModelPriceMapper priceMapper;
    private final TenantModelService tenantModelService;
    private final UpstreamTenantGuard tenantGuard;

    public TenantModelPriceService(TenantModelPriceMapper priceMapper,
                                   TenantModelService tenantModelService,
                                   UpstreamTenantGuard tenantGuard) {
        this.priceMapper = priceMapper;
        this.tenantModelService = tenantModelService;
        this.tenantGuard = tenantGuard;
    }

    @Transactional(readOnly = true)
    public Optional<TenantModelPriceView> currentPrice(Long tenantModelId,
                                                       UserAccount user,
                                                       Authentication auth) {
        tenantModelService.requireVisible(tenantModelId, user, auth);
        return priceMapper.findCurrent(tenantModelId);
    }

    @Transactional(readOnly = true)
    public List<TenantModelPriceView> priceHistory(Long tenantModelId,
                                                   UserAccount user,
                                                   Authentication auth) {
        tenantModelService.requireVisible(tenantModelId, user, auth);
        return priceMapper.findHistory(tenantModelId);
    }

    /**
     * 发布新价格版本（同事务内完成 expireCurrent + insert）。
     * 入参均为十进制字符串；通过 CnyPrecisionPolicy 严格校验 8 位小数与非负，绝不接受 float/double。
     */
    @Transactional
    public TenantModelPriceView publishPrice(Long tenantModelId,
                                              String inputPrice,
                                              String outputPrice,
                                              String cacheWritePrice,
                                              String cacheReadPrice,
                                              UserAccount user,
                                              Authentication auth) {
        TenantModel model = tenantModelService.requireVisible(tenantModelId, user, auth);
        tenantGuard.assertWritable(model.getTenantId());

        BigDecimal input = parseRequired(inputPrice, "输入单价");
        BigDecimal output = parseRequired(outputPrice, "输出单价");
        BigDecimal cacheWrite = parseOptional(cacheWritePrice);
        BigDecimal cacheRead = parseOptional(cacheReadPrice);

        // 能力一致性：支持缓存必须双价齐备；不支持缓存绝不允许缓存价
        if (model.isSupportsCache()) {
            if (cacheWrite == null || cacheRead == null) {
                throw new ModelException(BusinessErrorCode.TENANT_MODEL_INVALID,
                        "支持缓存的模型必须同时配置缓存读写价格");
            }
        } else {
            if (cacheWrite != null || cacheRead != null) {
                throw new ModelException(BusinessErrorCode.TENANT_MODEL_INVALID,
                        "未声明缓存能力的模型不得配置缓存价格");
            }
        }

        // 版本切换：先 expireCurrent，再 insert 下一版本；部分唯一索引兜底并发
        priceMapper.expireCurrent(tenantModelId);
        int nextVersion = priceMapper.findMaxVersion(tenantModelId) + 1;

        TenantModelPrice entity = new TenantModelPrice();
        entity.setTenantId(model.getTenantId());
        entity.setTenantModelId(tenantModelId);
        entity.setCurrencyCode(CnyPrecisionPolicy.CURRENCY_CODE);
        entity.setInputPricePerMillion(input);
        entity.setOutputPricePerMillion(output);
        entity.setCacheWritePricePerMillion(cacheWrite);
        entity.setCacheReadPricePerMillion(cacheRead);
        entity.setVersion(nextVersion);
        entity.setCreatedBy(user.getId());
        priceMapper.insert(entity);

        return priceMapper.findCurrent(tenantModelId)
                .orElseThrow(() -> new ModelException(BusinessErrorCode.INTERNAL_ERROR, "价格写入后无法读回"));
    }

    private static BigDecimal parseRequired(String text, String fieldDescription) {
        if (text == null || text.isBlank()) {
            throw new ModelException(BusinessErrorCode.TENANT_MODEL_INVALID,
                    fieldDescription + "不能为空");
        }
        try {
            return CnyPrecisionPolicy.toDecimal(text.trim());
        } catch (IllegalArgumentException ex) {
            throw new ModelException(BusinessErrorCode.TENANT_MODEL_INVALID,
                    fieldDescription + "格式不正确");
        }
    }

    private static BigDecimal parseOptional(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return CnyPrecisionPolicy.toDecimal(text.trim());
        } catch (IllegalArgumentException ex) {
            throw new ModelException(BusinessErrorCode.TENANT_MODEL_INVALID,
                    "缓存价格格式不正确");
        }
    }
}

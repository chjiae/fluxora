package io.fluxora.platform.model.mapper;

import io.fluxora.platform.model.dto.PublicTenantModel;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * C 端公开目录数据访问契约。
 * 单条联表查询完成 TenantModel + 当前有效价格的拼装；
 * 仅返回 publish_status='ENABLED' 且 enabled=TRUE 且未删除的模型；
 * 必须存在当前有效价格（tenant_model_price.expired_at IS NULL）
 * 并存在至少一个 enabled 候选映射 + 通道（提交 4 后追加路由+路由目标条件）。
 */
@Mapper
public interface PublicModelCatalogMapper {

    /** 返回当前租户所有真正对 C 端可见的模型；不暴露任何上游、候选、通道、凭证、路由细节。 */
    List<PublicTenantModel> findPublicModels(@Param("tenantId") Long tenantId);
}

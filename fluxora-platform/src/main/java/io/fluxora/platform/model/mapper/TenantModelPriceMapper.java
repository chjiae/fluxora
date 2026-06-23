package io.fluxora.platform.model.mapper;

import io.fluxora.platform.model.TenantModelPrice;
import io.fluxora.platform.model.dto.TenantModelPriceView;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 租户模型价格数据访问契约。
 * 价格版本不可篡改：写入只允许 INSERT；版本切换通过两条 SQL（expireCurrent + insert）在同一事务内完成。
 * 同一 tenant_model 同一时刻只能有一个 expired_at IS NULL 的当前版本（部分唯一索引兜底）。
 */
@Mapper
public interface TenantModelPriceMapper {

    void insert(TenantModelPrice entity);

    /** 将当前有效版本置为已失效；同事务内随后 insert 下一版本，保证「最多一个有效版本」不变量。 */
    int expireCurrent(@Param("tenantModelId") Long tenantModelId);

    /** 查询当前有效价格视图（金额字符串化由 Jackson 全局配置完成）。 */
    Optional<TenantModelPriceView> findCurrent(@Param("tenantModelId") Long tenantModelId);

    /** 价格历史按版本号倒序返回。 */
    List<TenantModelPriceView> findHistory(@Param("tenantModelId") Long tenantModelId);

    /** 取当前最大版本号；不存在则返回 0。事务内 SELECT…FOR UPDATE 由服务层加锁路径覆盖。 */
    int findMaxVersion(@Param("tenantModelId") Long tenantModelId);
}

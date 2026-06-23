package io.fluxora.platform.model;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.model.dto.PublicTenantModel;
import io.fluxora.platform.model.mapper.PublicModelCatalogMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C 端公开模型目录服务。
 * 始终按 JWT 当前用户所属租户隔离；平台管理员调用此接口时使用其自身 tenantId（一般为自营租户）。
 * 不接受任何 tenantId 入参以避免越权窥探其他租户对外目录。
 */
@Service
public class PublicModelCatalogService {

    private final PublicModelCatalogMapper publicMapper;

    public PublicModelCatalogService(PublicModelCatalogMapper publicMapper) {
        this.publicMapper = publicMapper;
    }

    @Transactional(readOnly = true)
    public List<PublicTenantModel> listForCurrentUser(UserAccount user) {
        if (user == null || user.getTenantId() == null) {
            // PLATFORM 作用域用户没有租户归属：公开目录与之无关，返回空集合，避免泄露任何租户数据
            throw new ModelException(BusinessErrorCode.ACCESS_DENIED, "当前账号没有此操作权限");
        }
        return publicMapper.findPublicModels(user.getTenantId());
    }
}

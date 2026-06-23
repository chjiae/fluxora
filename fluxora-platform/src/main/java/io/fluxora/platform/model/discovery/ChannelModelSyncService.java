package io.fluxora.platform.model.discovery;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.model.ModelException;
import io.fluxora.platform.model.ProviderChannelModel;
import io.fluxora.platform.model.mapper.ProviderChannelModelMapper;
import io.fluxora.platform.upstream.channel.ProviderChannel;
import io.fluxora.platform.upstream.channel.ProviderChannelMapper;
import io.fluxora.platform.upstream.provider.ProviderBaseUrl;
import io.fluxora.platform.upstream.provider.ProviderMapper;
import io.fluxora.platform.upstream.security.UpstreamTenantGuard;
import java.time.Instant;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通道模型同步编排：将同步机制与候选写入绑定，确保所有操作在 V10 租户隔离边界内。
 *
 * 同步规则：
 * - 同步后 insert 或 update 候选（source_type='SYNCED'）；
 * - 已存在的候选更新 last_synced_at；
 * - 本次未返回的候选仍然保留（不做物理删除），标记 last_sync_summary；
 * - 同步失败不删除任何已有候选、映射、价格或路由；
 * - 同一通道下相同 upstream_model_id 未删除时仅允许一条候选（部分唯一索引兜底）。
 */
@Service
public class ChannelModelSyncService {

    private final ProviderChannelMapper channelMapper;
    private final ProviderMapper providerMapper;
    private final ProviderChannelModelMapper channelModelMapper;
    private final ProviderModelDiscoveryService discoveryService;
    private final UpstreamTenantGuard tenantGuard;

    public ChannelModelSyncService(ProviderChannelMapper channelMapper,
                                   ProviderMapper providerMapper,
                                   ProviderChannelModelMapper channelModelMapper,
                                   ProviderModelDiscoveryService discoveryService,
                                   UpstreamTenantGuard tenantGuard) {
        this.channelMapper = channelMapper;
        this.providerMapper = providerMapper;
        this.channelModelMapper = channelModelMapper;
        this.discoveryService = discoveryService;
        this.tenantGuard = tenantGuard;
    }

    /**
     * 对指定通道执行模型同步。
     * 同步前校验可见性（通道必须属于当前租户或平台管理员以 admin+tenantId 代管）。
     */
    @Transactional
    public SyncResult sync(Long channelId, Long requestedCredentialId,
                           UserAccount user, Authentication auth) {
        ProviderChannel channel = channelMapper.findById(channelId)
                .orElseThrow(() -> new ModelException(BusinessErrorCode.RESOURCE_NOT_FOUND, "上游通道不存在"));
        // 平台管理员可为目标租户的代管通道同步；租户管理员只能操作本租户通道
        if (!tenantGuard.isPlatformAdmin(auth) && !channel.getTenantId().equals(user.getTenantId())) {
            throw new ModelException(BusinessErrorCode.ACCESS_DENIED, "当前账号没有此操作权限");
        }
        tenantGuard.assertWritable(channel.getTenantId());

        // 获取通道的协议与规范化地址
        ProviderBaseUrl baseUrl = providerMapper.findBaseUrlById(channel.getProviderBaseUrlId())
                .orElseThrow(() -> new ModelException(BusinessErrorCode.RESOURCE_NOT_FOUND, "接入地址不存在"));

        // 执行同步；SSRF 校验在 discoveryService 内部按模式判定
        ProviderModelDiscoveryService.DiscoveryResult result =
                discoveryService.discover(channelId, baseUrl.getNormalizedBaseUrl(),
                        baseUrl.getProtocol(), requestedCredentialId);

        // 计算 pre-existing 候选数量
        List<ProviderChannelModel> existing = channelModelMapper.findByChannel(channelId).stream()
                .map(s -> {
                    ProviderChannelModel m = new ProviderChannelModel();
                    m.setUpstreamModelId(s.upstreamModelId());
                    return m;
                }).toList();
        Set<String> existingIds = existing.stream()
                .map(ProviderChannelModel::getUpstreamModelId)
                .collect(Collectors.toSet());
        long beforeCount = existing.size();

        long added = 0;
        long updated = 0;
        List<String> thisBatch = new ArrayList<>();

        for (String upstreamId : result.upstreamIds()) {
            thisBatch.add(upstreamId);
            boolean exists = existingIds.contains(upstreamId);
            if (exists) {
                // 更新 last_synced_at
                channelModelMapper.updateLastSyncedAt(channelId, upstreamId);
                updated++;
            } else {
                // 新增候选
                ProviderChannelModel entity = new ProviderChannelModel();
                entity.setTenantId(channel.getTenantId());
                entity.setProviderChannelId(channelId);
                entity.setUpstreamModelId(upstreamId);
                entity.setUpstreamDisplayName(upstreamId);
                entity.setSourceType("SYNCED");
                entity.setEnabled(true);
                entity.setLastSyncedAt(Instant.now());
                channelModelMapper.insert(entity);
                added++;
            }
        }

        // 未返回的候选（仍在 existingIds 但不在 thisBatch）→ 不做物理删除，标记摘要
        long missing = existingIds.stream()
                .filter(id -> !thisBatch.contains(id))
                .count();

        if (missing > 0) {
            channelModelMapper.markMissing(channelId, thisBatch, "Not found in last sync", user.getId());
        }

        List<SyncItemResult> failures = result.failures().stream()
                .map(f -> new SyncItemResult(f.upstreamModelId(), f.result(), f.reason()))
                .toList();

        return new SyncResult(beforeCount, added, updated, missing, failures.size(), failures);
    }
}
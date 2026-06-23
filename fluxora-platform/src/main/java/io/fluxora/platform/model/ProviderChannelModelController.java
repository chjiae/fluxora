package io.fluxora.platform.model;

import io.fluxora.common.response.ApiResponse;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.model.discovery.ChannelModelSyncService;
import io.fluxora.platform.model.discovery.SyncResult;
import io.fluxora.platform.model.dto.ProviderChannelModelSummary;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 上游候选管理接口（挂在通道下作为子资源）。
 * 候选必须属于具体租户具体通道；服务层强制候选 tenant_id 与通道 tenant_id 一致。
 * 本提交仅提供纯候选 CRUD（无平台模型映射、无自动同步）；自动同步留待后续提交评估。
 */
@RestController
@RequestMapping("/api/provider-channels/{channelId}/models")
public class ProviderChannelModelController {

    private final ProviderChannelModelService service;
    private final ChannelModelSyncService syncService;

    public ProviderChannelModelController(ProviderChannelModelService service,
                                          ChannelModelSyncService syncService) {
        this.service = service;
        this.syncService = syncService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_READ')")
    public ResponseEntity<ApiResponse<List<ProviderChannelModelSummary>>> list(
            @PathVariable Long channelId) {
        return ResponseEntity.ok(ApiResponse.success(service.listByChannel(channelId)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_MANAGE')")
    public ResponseEntity<ApiResponse<ProviderChannelModelSummary>> create(
            @PathVariable Long channelId, @RequestBody CandidateRequest req,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                service.create(channelId, toEntity(req), user, auth)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_MANAGE')")
    public ResponseEntity<ApiResponse<ProviderChannelModelSummary>> update(
            @PathVariable Long channelId, @PathVariable Long id, @RequestBody CandidateRequest req,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                service.update(id, toEntity(req), user, auth)));
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_MANAGE')")
    public ResponseEntity<ApiResponse<Void>> enable(
            @PathVariable Long channelId, @PathVariable Long id,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        service.setEnabled(id, true, user, auth);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_MANAGE')")
    public ResponseEntity<ApiResponse<Void>> disable(
            @PathVariable Long channelId, @PathVariable Long id,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        service.setEnabled(id, false, user, auth);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_MANAGE')")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable Long channelId, @PathVariable Long id,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        service.delete(id, user, auth);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * 触发该通道下的上游模型同步。
     *
     * - 通道与凭证 tenant 一致性由服务层保证；
     * - 凭证明文仅在服务端短暂解密，不写入响应；
     * - 不支持自动发现的协议（如 ANTHROPIC）返回空候选 + 0 计数，前端引导手工新增；
     * - 同步失败不会删除现有候选 / 映射 / 路由 / 价格；
     * - 响应只含计数与逐项安全原因，不含上游原始内容、HTTP 状态码或异常文本。
     */
    @PostMapping("/sync")
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_MANAGE')")
    public ResponseEntity<ApiResponse<SyncResult>> sync(
            @PathVariable Long channelId,
            @RequestBody(required = false) SyncRequest req,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        Long credId = req == null ? null : req.credentialId();
        return ResponseEntity.ok(ApiResponse.success(syncService.sync(channelId, credId, user, auth)));
    }

    public record SyncRequest(Long credentialId) {
    }

    private ProviderChannelModel toEntity(CandidateRequest r) {
        ProviderChannelModel m = new ProviderChannelModel();
        m.setUpstreamModelId(r.upstreamModelId());
        m.setUpstreamDisplayName(r.upstreamDisplayName());
        m.setSupportsStreaming(Boolean.TRUE.equals(r.supportsStreaming()));
        m.setSupportsToolCalling(Boolean.TRUE.equals(r.supportsToolCalling()));
        m.setSupportsVision(Boolean.TRUE.equals(r.supportsVision()));
        m.setSupportsCache(Boolean.TRUE.equals(r.supportsCache()));
        m.setEnabled(r.enabled() == null || r.enabled());
        return m;
    }

    public record CandidateRequest(
            String upstreamModelId,
            String upstreamDisplayName,
            Boolean supportsStreaming,
            Boolean supportsToolCalling,
            Boolean supportsVision,
            Boolean supportsCache,
            Boolean enabled
    ) {
    }
}
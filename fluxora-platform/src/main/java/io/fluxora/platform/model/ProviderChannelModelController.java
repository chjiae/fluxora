package io.fluxora.platform.model;

import io.fluxora.common.response.ApiResponse;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.upstream.dto.UpstreamPage;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 上游模型候选接口：手工维护是所有不支持自动发现上游的安全兜底。 */
@RestController @RequestMapping("/api/provider-channels/{channelId}/models")
public class ProviderChannelModelController {
 private final ModelCatalogService service; public ProviderChannelModelController(ModelCatalogService service){this.service=service;}
 @GetMapping @PreAuthorize("hasAuthority('PERM_UPSTREAM_READ')") public ResponseEntity<ApiResponse<UpstreamPage<ProviderChannelModelSummary>>> list(@PathVariable Long channelId,@AuthenticationPrincipal UserAccount user,Authentication auth,@RequestParam(defaultValue="") String keyword,@RequestParam(required=false) Boolean enabled,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ResponseEntity.ok(ApiResponse.success(service.listChannelModels(channelId,user,auth,keyword,enabled,page,size)));}
 @PostMapping @PreAuthorize("hasAuthority('PERM_UPSTREAM_CREATE')") public ResponseEntity<ApiResponse<ProviderChannelModelSummary>> create(@PathVariable Long channelId,@RequestBody ManualChannelModelRequest request,@AuthenticationPrincipal UserAccount user,Authentication auth){return ResponseEntity.ok(ApiResponse.success(service.createManualChannelModel(channelId,request.upstreamModelId(),request.displayName(),user,auth)));}
 @PutMapping("/{id}") @PreAuthorize("hasAuthority('PERM_UPSTREAM_UPDATE')") public ResponseEntity<ApiResponse<ProviderChannelModelSummary>> update(@PathVariable Long channelId,@PathVariable Long id,@RequestBody UpdateChannelModelRequest request,@AuthenticationPrincipal UserAccount user,Authentication auth){return ResponseEntity.ok(ApiResponse.success(service.updateChannelModel(id,request.displayName(),Boolean.TRUE.equals(request.enabled()),user,auth)));}
 @DeleteMapping("/{id}") @PreAuthorize("hasAuthority('PERM_UPSTREAM_DELETE')") public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long channelId,@PathVariable Long id,@AuthenticationPrincipal UserAccount user,Authentication auth){service.deleteChannelModel(id,user,auth);return ResponseEntity.ok(ApiResponse.success(null));}
 @PutMapping("/{id}/mapping") @PreAuthorize("hasAuthority('PERM_MODEL_PLATFORM_MANAGE')") public ResponseEntity<ApiResponse<ProviderChannelModelSummary>> mapping(@PathVariable Long channelId,@PathVariable Long id,@RequestBody MappingRequest request,@AuthenticationPrincipal UserAccount user,Authentication auth){return ResponseEntity.ok(ApiResponse.success(service.mapChannelModel(id,request.platformModelId(),user,auth)));}
 @PostMapping("/sync") @PreAuthorize("hasAuthority('PERM_UPSTREAM_UPDATE')") public ResponseEntity<ApiResponse<SyncResult>> sync(@PathVariable Long channelId,@RequestBody(required=false) SyncRequest request,@AuthenticationPrincipal UserAccount user,Authentication auth){return ResponseEntity.ok(ApiResponse.success(service.syncChannelModels(channelId,request==null?null:request.credentialId(),user,auth)));}
 public record ManualChannelModelRequest(String upstreamModelId,String displayName){}
 public record UpdateChannelModelRequest(String displayName,Boolean enabled){}
 public record MappingRequest(Long platformModelId){}
 public record SyncRequest(Long credentialId){}
}

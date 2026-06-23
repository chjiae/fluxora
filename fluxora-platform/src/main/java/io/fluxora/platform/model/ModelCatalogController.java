package io.fluxora.platform.model;

import io.fluxora.common.response.ApiResponse;
import io.fluxora.platform.upstream.dto.UpstreamPage;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import io.fluxora.platform.identity.entity.UserAccount;

/** 平台模型库 API；目录读取与平台写权限将分离，避免租户管理员误改全局目录。 */
@RestController
@RequestMapping("/api/platform-models")
public class ModelCatalogController {
    private final ModelCatalogService service;
    public ModelCatalogController(ModelCatalogService service) { this.service = service; }
    @GetMapping
    @PreAuthorize("hasAuthority('PERM_MODEL_CATALOG_READ')")
    public ResponseEntity<ApiResponse<UpstreamPage<PlatformModelSummary>>> list(@RequestParam(defaultValue="") String keyword, @RequestParam(required=false) Boolean enabled, @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size) {
        return ResponseEntity.ok(ApiResponse.success(service.listPlatformModels(keyword,enabled,page,size)));
    }
    @PostMapping @PreAuthorize("hasAuthority('PERM_MODEL_PLATFORM_MANAGE')")
    public ResponseEntity<ApiResponse<PlatformModelSummary>> create(@RequestBody PlatformModelRequest request,@AuthenticationPrincipal UserAccount user,Authentication auth){return ResponseEntity.ok(ApiResponse.success(service.createPlatformModel(toModel(request),user,auth)));}
    @PutMapping("/{id}") @PreAuthorize("hasAuthority('PERM_MODEL_PLATFORM_MANAGE')")
    public ResponseEntity<ApiResponse<PlatformModelSummary>> update(@PathVariable Long id,@RequestBody PlatformModelRequest request,@AuthenticationPrincipal UserAccount user,Authentication auth){return ResponseEntity.ok(ApiResponse.success(service.updatePlatformModel(id,toModel(request),user,auth)));}
    @PutMapping("/{id}/enable") @PreAuthorize("hasAuthority('PERM_MODEL_PLATFORM_MANAGE')") public ResponseEntity<ApiResponse<Void>> enable(@PathVariable Long id,Authentication auth){service.setPlatformModelEnabled(id,true,auth);return ResponseEntity.ok(ApiResponse.success(null));}
    @PutMapping("/{id}/disable") @PreAuthorize("hasAuthority('PERM_MODEL_PLATFORM_MANAGE')") public ResponseEntity<ApiResponse<Void>> disable(@PathVariable Long id,Authentication auth){service.setPlatformModelEnabled(id,false,auth);return ResponseEntity.ok(ApiResponse.success(null));}
    private PlatformModel toModel(PlatformModelRequest r){PlatformModel m=new PlatformModel();m.setCode(r.code());m.setDisplayName(r.displayName());m.setDescription(r.description());m.setModelType(r.modelType());m.setTags(r.tags());m.setSupportsStreaming(Boolean.TRUE.equals(r.supportsStreaming()));m.setSupportsTools(Boolean.TRUE.equals(r.supportsTools()));m.setSupportsVision(Boolean.TRUE.equals(r.supportsVision()));m.setSupportsCache(Boolean.TRUE.equals(r.supportsCache()));m.setContextLength(r.contextLength());m.setMaxOutputLength(r.maxOutputLength());m.setEnabled(r.enabled()==null||r.enabled());return m;}
    public record PlatformModelRequest(String code,String displayName,String description,String modelType,String tags,Boolean supportsStreaming,Boolean supportsTools,Boolean supportsVision,Boolean supportsCache,Long contextLength,Long maxOutputLength,Boolean enabled){}
    @GetMapping("/{id}/prices/current") @PreAuthorize("hasAuthority('PERM_MODEL_PLATFORM_MANAGE')") public ResponseEntity<ApiResponse<PriceView>> currentPrice(@PathVariable Long id){return ResponseEntity.ok(ApiResponse.success(service.currentPlatformPrice(id)));}
    @GetMapping("/{id}/prices") @PreAuthorize("hasAuthority('PERM_MODEL_PLATFORM_MANAGE')") public ResponseEntity<ApiResponse<java.util.List<PriceView>>> prices(@PathVariable Long id,Authentication auth){return ResponseEntity.ok(ApiResponse.success(service.platformPriceHistory(id,auth)));}
    @PostMapping("/{id}/prices") @PreAuthorize("hasAuthority('PERM_MODEL_PLATFORM_MANAGE')") public ResponseEntity<ApiResponse<PriceView>> createPrice(@PathVariable Long id,@RequestBody PriceRequest r,@AuthenticationPrincipal UserAccount user,Authentication auth){return ResponseEntity.ok(ApiResponse.success(service.createPlatformPrice(id,r.inputPrice(),r.outputPrice(),r.cacheWritePrice(),r.cacheReadPrice(),user,auth)));}
    public record PriceRequest(String inputPrice,String outputPrice,String cacheWritePrice,String cacheReadPrice){}
}

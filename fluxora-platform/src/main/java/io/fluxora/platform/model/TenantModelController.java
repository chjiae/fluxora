package io.fluxora.platform.model;

import io.fluxora.common.response.ApiResponse;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.upstream.dto.UpstreamPage;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 租户模型发布接口：创建后保持 DRAFT，避免绕过路由与价格前置条件直接对外可见。 */
@RestController @RequestMapping("/api/tenant-models")
public class TenantModelController {
 private final ModelCatalogService service; public TenantModelController(ModelCatalogService service){this.service=service;}
 @GetMapping @PreAuthorize("hasAuthority('PERM_MODEL_CATALOG_MANAGE')") public ResponseEntity<ApiResponse<UpstreamPage<TenantModelSummary>>> list(@RequestParam(required=false) Long tenantId,@AuthenticationPrincipal UserAccount user,Authentication auth,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ResponseEntity.ok(ApiResponse.success(service.listTenantModels(tenantId,user,auth,page,size)));}
 @PostMapping @PreAuthorize("hasAuthority('PERM_MODEL_CATALOG_MANAGE')") public ResponseEntity<ApiResponse<TenantModelSummary>> publish(@RequestBody TenantModelRequest request,@AuthenticationPrincipal UserAccount user,Authentication auth){return ResponseEntity.ok(ApiResponse.success(service.publishTenantModel(request.tenantId(),request.platformModelId(),request.displayName(),request.description(),user,auth)));}
 public record TenantModelRequest(Long tenantId,Long platformModelId,String displayName,String description){}
 @GetMapping("/{id}/prices/current") @PreAuthorize("hasAuthority('PERM_MODEL_CATALOG_READ')") public ResponseEntity<ApiResponse<PriceView>> price(@PathVariable Long id,@AuthenticationPrincipal UserAccount user,Authentication auth){return ResponseEntity.ok(ApiResponse.success(service.currentTenantPrice(id,user,auth)));}
 @GetMapping("/{id}/prices") @PreAuthorize("hasAuthority('PERM_MODEL_CATALOG_MANAGE')") public ResponseEntity<ApiResponse<java.util.List<PriceView>>> prices(@PathVariable Long id,@AuthenticationPrincipal UserAccount user,Authentication auth){return ResponseEntity.ok(ApiResponse.success(service.tenantPriceHistory(id,user,auth)));}
 @PostMapping("/{id}/prices/custom") @PreAuthorize("hasAuthority('PERM_MODEL_CATALOG_MANAGE')") public ResponseEntity<ApiResponse<PriceView>> custom(@PathVariable Long id,@RequestBody PriceRequest r,@AuthenticationPrincipal UserAccount user,Authentication auth){return ResponseEntity.ok(ApiResponse.success(service.setTenantCustomPrice(id,r.inputPrice(),r.outputPrice(),r.cacheWritePrice(),r.cacheReadPrice(),user,auth)));}
 @PutMapping("/{id}/prices/inherit") @PreAuthorize("hasAuthority('PERM_MODEL_CATALOG_MANAGE')") public ResponseEntity<ApiResponse<Void>> inherit(@PathVariable Long id,@AuthenticationPrincipal UserAccount user,Authentication auth){service.inheritPlatformPrice(id,user,auth);return ResponseEntity.ok(ApiResponse.success(null));}
 @PutMapping("/{id}/enable") @PreAuthorize("hasAuthority('PERM_MODEL_CATALOG_MANAGE')") public ResponseEntity<ApiResponse<Void>> enable(@PathVariable Long id,@AuthenticationPrincipal UserAccount user,Authentication auth){service.enableTenantModel(id,user,auth);return ResponseEntity.ok(ApiResponse.success(null));}
 public record PriceRequest(String inputPrice,String outputPrice,String cacheWritePrice,String cacheReadPrice){}
}

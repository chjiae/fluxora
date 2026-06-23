package io.fluxora.platform.model;

import io.fluxora.common.response.ApiResponse;
import io.fluxora.platform.identity.entity.UserAccount;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 路由控制面：保存合法映射，当前不参与真实网关转发或协议转换。 */
@RestController @RequestMapping("/api/tenant-models")
public class ModelRouteController {
 private final ModelCatalogService service; public ModelRouteController(ModelCatalogService service){this.service=service;}
 @GetMapping("/{modelId}/routes") @PreAuthorize("hasAuthority('PERM_MODEL_CATALOG_MANAGE')") public ResponseEntity<ApiResponse<List<ModelRouteSummary>>> routes(@PathVariable Long modelId,@AuthenticationPrincipal UserAccount user,Authentication auth){return ResponseEntity.ok(ApiResponse.success(service.routes(modelId,user,auth)));}
 @PostMapping("/{modelId}/routes") @PreAuthorize("hasAuthority('PERM_MODEL_CATALOG_MANAGE')") public ResponseEntity<ApiResponse<ModelRouteSummary>> createRoute(@PathVariable Long modelId,@RequestBody RouteRequest r,@AuthenticationPrincipal UserAccount user,Authentication auth){return ResponseEntity.ok(ApiResponse.success(service.createRoute(modelId,r.inboundProtocol(),r.remark(),user,auth)));}
 @PutMapping("/routes/{routeId}") @PreAuthorize("hasAuthority('PERM_MODEL_CATALOG_MANAGE')") public ResponseEntity<ApiResponse<Void>> updateRoute(@PathVariable Long routeId,@RequestBody RouteUpdateRequest r,@AuthenticationPrincipal UserAccount user,Authentication auth){service.updateRoute(routeId,Boolean.TRUE.equals(r.enabled()),r.remark(),user,auth);return ResponseEntity.ok(ApiResponse.success(null));}
 @DeleteMapping("/routes/{routeId}") @PreAuthorize("hasAuthority('PERM_MODEL_CATALOG_MANAGE')") public ResponseEntity<ApiResponse<Void>> deleteRoute(@PathVariable Long routeId,@AuthenticationPrincipal UserAccount user,Authentication auth){service.deleteRoute(routeId,user,auth);return ResponseEntity.ok(ApiResponse.success(null));}
 @GetMapping("/routes/{routeId}/targets") @PreAuthorize("hasAuthority('PERM_MODEL_CATALOG_MANAGE')") public ResponseEntity<ApiResponse<List<RouteTargetSummary>>> targets(@PathVariable Long routeId,@AuthenticationPrincipal UserAccount user,Authentication auth){return ResponseEntity.ok(ApiResponse.success(service.targets(routeId,user,auth)));}
 @PostMapping("/routes/{routeId}/targets") @PreAuthorize("hasAuthority('PERM_MODEL_CATALOG_MANAGE')") public ResponseEntity<ApiResponse<Void>> target(@PathVariable Long routeId,@RequestBody TargetRequest r,@AuthenticationPrincipal UserAccount user,Authentication auth){service.createTarget(routeId,r.providerChannelId(),r.providerChannelModelId(),r.priority(),r.weight(),r.remark(),user,auth);return ResponseEntity.ok(ApiResponse.success(null));}
 @PutMapping("/routes/{routeId}/targets/{targetId}") @PreAuthorize("hasAuthority('PERM_MODEL_CATALOG_MANAGE')") public ResponseEntity<ApiResponse<Void>> updateTarget(@PathVariable Long routeId,@PathVariable Long targetId,@RequestBody TargetUpdateRequest r,@AuthenticationPrincipal UserAccount user,Authentication auth){service.updateTarget(routeId,targetId,r.priority(),r.weight(),Boolean.TRUE.equals(r.enabled()),r.remark(),user,auth);return ResponseEntity.ok(ApiResponse.success(null));}
 @DeleteMapping("/routes/{routeId}/targets/{targetId}") @PreAuthorize("hasAuthority('PERM_MODEL_CATALOG_MANAGE')") public ResponseEntity<ApiResponse<Void>> deleteTarget(@PathVariable Long routeId,@PathVariable Long targetId,@AuthenticationPrincipal UserAccount user,Authentication auth){service.deleteTarget(routeId,targetId,user,auth);return ResponseEntity.ok(ApiResponse.success(null));}
 public record RouteRequest(String inboundProtocol,String remark){} public record RouteUpdateRequest(Boolean enabled,String remark){} public record TargetRequest(Long providerChannelId,Long providerChannelModelId,Integer priority,Integer weight,String remark){} public record TargetUpdateRequest(Integer priority,Integer weight,Boolean enabled,String remark){}
}

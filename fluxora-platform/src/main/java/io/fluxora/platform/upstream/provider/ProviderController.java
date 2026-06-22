package io.fluxora.platform.upstream.provider;

import io.fluxora.common.response.ApiResponse;
import io.fluxora.platform.identity.entity.UserAccount;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 上游厂商 REST 接口；共享资源可读不可由租户管理员写，最终边界在 ProviderService。 */
@RestController
@RequestMapping("/api/providers")
public class ProviderController {
    private final ProviderMapper mapper; private final ProviderService service;
    public ProviderController(ProviderMapper mapper, ProviderService service) { this.mapper = mapper; this.service = service; }
    @GetMapping @PreAuthorize("hasAuthority('PERM_UPSTREAM_READ')")
    public ResponseEntity<ApiResponse<Page>> list(@AuthenticationPrincipal UserAccount user, Authentication auth,
            @RequestParam(defaultValue = "") String keyword, @RequestParam(defaultValue = "") String scopeType,
            @RequestParam(required = false) Boolean enabled, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        int safeSize=Math.max(1,Math.min(size,100)); int safePage=Math.max(page,1); boolean platform=service.isPlatformAdmin(auth);
        List<Provider> items=mapper.findPage(user.getTenantId(),platform,keyword.isBlank()?null:keyword,scopeType.isBlank()?null:scopeType,enabled,(safePage-1)*safeSize,safeSize);
        return ResponseEntity.ok(ApiResponse.success(new Page(items,mapper.countPage(user.getTenantId(),platform,keyword.isBlank()?null:keyword,scopeType.isBlank()?null:scopeType,enabled),safePage,safeSize)));
    }
    @GetMapping("/{id}") @PreAuthorize("hasAuthority('PERM_UPSTREAM_READ')")
    public ResponseEntity<ApiResponse<Provider>> detail(@PathVariable Long id,@AuthenticationPrincipal UserAccount user,Authentication auth){return ResponseEntity.ok(ApiResponse.success(service.requireVisible(id,user,auth)));}
    @PostMapping @PreAuthorize("hasAuthority('PERM_UPSTREAM_CREATE')")
    public ResponseEntity<ApiResponse<Provider>> create(@RequestBody CreateRequest req,@AuthenticationPrincipal UserAccount user,Authentication auth){ Provider p=new Provider();p.setName(req.name());p.setCode(req.code());p.setScopeType(req.scopeType());p.setDescription(req.description());p.setEnabled(req.enabled()==null||req.enabled());return ResponseEntity.ok(ApiResponse.success(service.create(p,user,auth))); }
    @PutMapping("/{id}") @PreAuthorize("hasAuthority('PERM_UPSTREAM_UPDATE')")
    public ResponseEntity<ApiResponse<Provider>> update(@PathVariable Long id,@RequestBody UpdateRequest req,@AuthenticationPrincipal UserAccount user,Authentication auth){Provider p=new Provider();p.setName(req.name());p.setDescription(req.description());return ResponseEntity.ok(ApiResponse.success(service.update(id,p,user,auth)));}
    @PutMapping("/{id}/enable") @PreAuthorize("hasAuthority('PERM_UPSTREAM_ENABLE')") public ResponseEntity<ApiResponse<Void>> enable(@PathVariable Long id,@AuthenticationPrincipal UserAccount u,Authentication a){Provider p=service.requireVisible(id,u,a);service.assertWritable(p,u,a);mapper.setEnabled(id,true);return ResponseEntity.ok(ApiResponse.success(null));}
    @PutMapping("/{id}/disable") @PreAuthorize("hasAuthority('PERM_UPSTREAM_DISABLE')") public ResponseEntity<ApiResponse<Void>> disable(@PathVariable Long id,@AuthenticationPrincipal UserAccount u,Authentication a){Provider p=service.requireVisible(id,u,a);service.assertWritable(p,u,a);mapper.setEnabled(id,false);return ResponseEntity.ok(ApiResponse.success(null));}
    @DeleteMapping("/{id}") @PreAuthorize("hasAuthority('PERM_UPSTREAM_DELETE')") public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id,@AuthenticationPrincipal UserAccount u,Authentication a){Provider p=service.requireVisible(id,u,a);service.assertWritable(p,u,a);if(mapper.hasBaseUrls(id))throw new ProviderException(io.fluxora.common.error.BusinessErrorCode.VALIDATION_ERROR,"该上游厂商仍有关联配置，无法删除");mapper.softDelete(id);return ResponseEntity.ok(ApiResponse.success(null));}
    public record CreateRequest(String name,String code,String scopeType,String description,Boolean enabled){} public record UpdateRequest(String name,String description){} public record Page(List<Provider> items,long total,int page,int size){}
}

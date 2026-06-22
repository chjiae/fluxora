package io.fluxora.platform.upstream.provider;

import io.fluxora.common.response.ApiResponse;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.upstream.ProviderBaseUrlNormalizer;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 接入地址接口：所有写操作先复用 ProviderService 的共享/私有作用域保护。 */
@RestController
@RequestMapping("/api/provider-base-urls")
public class ProviderBaseUrlController {
    private final ProviderMapper mapper; private final ProviderService providerService; private final ProviderBaseUrlNormalizer normalizer;
    public ProviderBaseUrlController(ProviderMapper m,ProviderService s,ProviderBaseUrlNormalizer n){mapper=m;providerService=s;normalizer=n;}
    @GetMapping @PreAuthorize("hasAuthority('PERM_UPSTREAM_READ')")
    public ResponseEntity<ApiResponse<java.util.List<ProviderBaseUrl>>> list(@RequestParam Long providerId,@AuthenticationPrincipal UserAccount user,Authentication auth){providerService.requireVisible(providerId,user,auth);return ResponseEntity.ok(ApiResponse.success(mapper.findBaseUrls(providerId)));}
    @PostMapping @PreAuthorize("hasAuthority('PERM_UPSTREAM_CREATE')")
    public ResponseEntity<ApiResponse<ProviderBaseUrl>> create(@RequestBody Request r,@AuthenticationPrincipal UserAccount user,Authentication auth){Provider p=providerService.requireVisible(r.providerId(),user,auth);providerService.assertWritable(p,user,auth);ProviderBaseUrl b=build(r);mapper.insertBaseUrl(b);return ResponseEntity.ok(ApiResponse.success(b));}
    @PutMapping("/{id}") @PreAuthorize("hasAuthority('PERM_UPSTREAM_UPDATE')")
    public ResponseEntity<ApiResponse<ProviderBaseUrl>> update(@PathVariable Long id,@RequestBody Request r,@AuthenticationPrincipal UserAccount user,Authentication auth){ProviderBaseUrl b=mapper.findBaseUrlById(id).orElseThrow(()->new ProviderException(io.fluxora.common.error.BusinessErrorCode.RESOURCE_NOT_FOUND,"接入地址不存在"));Provider p=providerService.requireVisible(b.getProviderId(),user,auth);providerService.assertWritable(p,user,auth);ProviderBaseUrl x=build(r);b.setProtocol(x.getProtocol());b.setOriginalBaseUrl(x.getOriginalBaseUrl());b.setNormalizedBaseUrl(x.getNormalizedBaseUrl());b.setDisplayName(x.getDisplayName());b.setRemark(x.getRemark());mapper.updateBaseUrl(b);return ResponseEntity.ok(ApiResponse.success(b));}
    @PutMapping("/{id}/{state}") @PreAuthorize("hasAuthority('PERM_UPSTREAM_UPDATE')")
    public ResponseEntity<ApiResponse<Void>> state(@PathVariable Long id,@PathVariable String state,@AuthenticationPrincipal UserAccount u,Authentication a){ProviderBaseUrl b=mapper.findBaseUrlById(id).orElseThrow(()->new ProviderException(io.fluxora.common.error.BusinessErrorCode.RESOURCE_NOT_FOUND,"接入地址不存在"));Provider p=providerService.requireVisible(b.getProviderId(),u,a);providerService.assertWritable(p,u,a);mapper.setBaseUrlEnabled(id,"enable".equals(state));return ResponseEntity.ok(ApiResponse.success(null));}
    @DeleteMapping("/{id}") @PreAuthorize("hasAuthority('PERM_UPSTREAM_DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id,@AuthenticationPrincipal UserAccount u,Authentication a){ProviderBaseUrl b=mapper.findBaseUrlById(id).orElseThrow(()->new ProviderException(io.fluxora.common.error.BusinessErrorCode.RESOURCE_NOT_FOUND,"接入地址不存在"));Provider p=providerService.requireVisible(b.getProviderId(),u,a);providerService.assertWritable(p,u,a);if(mapper.hasChannelsByBaseUrl(id))throw new ProviderException(io.fluxora.common.error.BusinessErrorCode.VALIDATION_ERROR,"该接入地址仍被通道使用，无法删除");mapper.softDeleteBaseUrl(id);return ResponseEntity.ok(ApiResponse.success(null));}
    private ProviderBaseUrl build(Request r){if(!"OPENAI".equals(r.protocol())&&!"ANTHROPIC".equals(r.protocol()))throw new ProviderException(io.fluxora.common.error.BusinessErrorCode.VALIDATION_ERROR,"请选择有效的上游协议");ProviderBaseUrl b=new ProviderBaseUrl();b.setProviderId(r.providerId());b.setProtocol(r.protocol());b.setOriginalBaseUrl(r.baseUrl());b.setNormalizedBaseUrl(normalizer.normalize(r.baseUrl()));b.setDisplayName(r.displayName());b.setRemark(r.remark());b.setEnabled(true);return b;}
    public record Request(Long providerId,String protocol,String baseUrl,String displayName,String remark){}
}

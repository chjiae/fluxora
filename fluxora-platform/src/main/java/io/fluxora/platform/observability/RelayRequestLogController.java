package io.fluxora.platform.observability;

import io.fluxora.common.response.ApiResponse;
import io.fluxora.platform.identity.entity.UserAccount;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 安全请求日志接口；服务层承担平台/租户/普通成员的强制数据隔离。 */
@RestController @RequestMapping("/api/request-logs")
public class RelayRequestLogController {
    private final RelayRequestLogService service; public RelayRequestLogController(RelayRequestLogService service){this.service=service;}
    @GetMapping public ResponseEntity<ApiResponse<RelayRequestLogService.RelayLogPage>> list(@AuthenticationPrincipal UserAccount u,Authentication a,@RequestParam(required=false) Long tenantId,@RequestParam(required=false) String requestId,@RequestParam(required=false) String tenantModelCode,@RequestParam(required=false) String protocol,@RequestParam(required=false) Long apiKeyId,@RequestParam(required=false) Long userId,@RequestParam(required=false) String requestStatus,@RequestParam(required=false) Instant startAt,@RequestParam(required=false) Instant endAt,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ResponseEntity.ok(ApiResponse.success(service.list(u,a,f(tenantId,requestId,tenantModelCode,protocol,apiKeyId,userId,requestStatus,startAt,endAt,page,size))));}
    @GetMapping("/stats") public ResponseEntity<ApiResponse<RelayRequestLogStats>> stats(@AuthenticationPrincipal UserAccount u,Authentication a,@RequestParam(required=false) Long tenantId,@RequestParam(required=false) String tenantModelCode,@RequestParam(required=false) String protocol,@RequestParam(required=false) Long apiKeyId,@RequestParam(required=false) Long userId,@RequestParam(required=false) String requestStatus,@RequestParam(required=false) Instant startAt,@RequestParam(required=false) Instant endAt){return ResponseEntity.ok(ApiResponse.success(service.stats(u,a,f(tenantId,null,tenantModelCode,protocol,apiKeyId,userId,requestStatus,startAt,endAt,1,1))));}
    @GetMapping("/trends") public ResponseEntity<ApiResponse<RelayRequestLogService.RelayTrendResponse>> trends(@AuthenticationPrincipal UserAccount u,Authentication a,@RequestParam(required=false) Long tenantId,@RequestParam(defaultValue="TODAY") String range,@RequestParam(required=false) String tenantModelCode,@RequestParam(required=false) String protocol,@RequestParam(required=false) Long apiKeyId,@RequestParam(required=false) Long userId,@RequestParam(required=false) String requestStatus){return ResponseEntity.ok(ApiResponse.success(service.trends(u,a,f(tenantId,null,tenantModelCode,protocol,apiKeyId,userId,requestStatus,null,null,1,100),range)));}
    @GetMapping("/price-preview") public ResponseEntity<ApiResponse<RelayRequestLogService.PricePreview>> preview(@RequestParam String inputTokens,@RequestParam String outputTokens,@RequestParam(required=false) String cacheWriteTokens,@RequestParam(required=false) String cacheReadTokens,@RequestParam String inputPricePerMillion,@RequestParam String outputPricePerMillion,@RequestParam(required=false) String cacheWritePricePerMillion,@RequestParam(required=false) String cacheReadPricePerMillion){return ResponseEntity.ok(ApiResponse.success(service.preview(inputTokens,outputTokens,cacheWriteTokens,cacheReadTokens,inputPricePerMillion,outputPricePerMillion,cacheWritePricePerMillion,cacheReadPricePerMillion)));}
    @GetMapping("/{requestId}") public ResponseEntity<ApiResponse<RelayRequestLogDetail>> detail(@PathVariable String requestId,@AuthenticationPrincipal UserAccount u,Authentication a,@RequestParam(required=false) Long tenantId){return ResponseEntity.ok(ApiResponse.success(service.detail(u,a,tenantId,requestId)));}
    private RelayRequestLogService.RequestLogFilter f(Long t,String r,String m,String p,Long k,Long u,String s,Instant start,Instant end,int page,int size){return new RelayRequestLogService.RequestLogFilter(t,r,m,p,k,u,s,start,end,page,size);}
}

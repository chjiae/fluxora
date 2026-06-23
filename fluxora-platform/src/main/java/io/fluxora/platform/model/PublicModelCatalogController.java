package io.fluxora.platform.model;

import io.fluxora.common.response.ApiResponse;
import io.fluxora.platform.identity.entity.UserAccount;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 普通成员可见模型目录，使用单独 DTO 防止管理面字段意外外泄。 */
@RestController @RequestMapping("/api/models")
public class PublicModelCatalogController {
 private final ModelCatalogService service; public PublicModelCatalogController(ModelCatalogService service){this.service=service;}
 @GetMapping @PreAuthorize("hasAuthority('PERM_MODEL_CATALOG_READ')") public ResponseEntity<ApiResponse<List<PublicModelCatalogItem>>> list(@AuthenticationPrincipal UserAccount user){return ResponseEntity.ok(ApiResponse.success(service.publicModels(user)));}
}

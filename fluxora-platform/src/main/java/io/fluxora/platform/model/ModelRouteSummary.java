package io.fluxora.platform.model;

/** 租户模型协议路由管理投影；目标详情由独立 RouteTargetSummary 返回。 */
public class ModelRouteSummary { private Long id,tenantModelId; private String inboundProtocol,status,remark;
 public Long getId(){return id;} public void setId(Long v){id=v;} public Long getTenantModelId(){return tenantModelId;} public void setTenantModelId(Long v){tenantModelId=v;} public String getInboundProtocol(){return inboundProtocol;} public void setInboundProtocol(String v){inboundProtocol=v;} public String getStatus(){return status;} public void setStatus(String v){status=v;} public String getRemark(){return remark;} public void setRemark(String v){remark=v;} }

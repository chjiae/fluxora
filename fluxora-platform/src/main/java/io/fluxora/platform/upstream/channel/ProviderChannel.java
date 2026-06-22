package io.fluxora.platform.upstream.channel;

import java.time.Instant;

/**
 * 租户实际可使用的上游通道。
 * 通道只保存未来路由需要的运行参数，当前不承担请求转发、模型选择或健康检查职责。
 */
public class ProviderChannel {
    private Long id;
    /** 通道所属租户；租户管理员的所有写入均由服务层强制使用当前租户。 */
    private Long tenantId;
    /** 引用的逻辑接入基础地址，必须属于共享 Provider 或当前租户私有 Provider。 */
    private Long providerBaseUrlId;
    private String name;
    private boolean enabled;
    private int priority;
    private int weight;
    private int connectTimeoutMs;
    private int readTimeoutMs;
    private String remark;
    /** NULL 表示未删除；删除后不得继续被凭证写入。 */
    private Instant deletedAt;
    private Instant createdAt;
    private Instant updatedAt;
    public Long getId(){return id;} public void setId(Long value){id=value;}
    public Long getTenantId(){return tenantId;} public void setTenantId(Long value){tenantId=value;}
    public Long getProviderBaseUrlId(){return providerBaseUrlId;} public void setProviderBaseUrlId(Long value){providerBaseUrlId=value;}
    public String getName(){return name;} public void setName(String value){name=value;}
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean value){enabled=value;}
    public int getPriority(){return priority;} public void setPriority(int value){priority=value;}
    public int getWeight(){return weight;} public void setWeight(int value){weight=value;}
    public int getConnectTimeoutMs(){return connectTimeoutMs;} public void setConnectTimeoutMs(int value){connectTimeoutMs=value;}
    public int getReadTimeoutMs(){return readTimeoutMs;} public void setReadTimeoutMs(int value){readTimeoutMs=value;}
    public String getRemark(){return remark;} public void setRemark(String value){remark=value;}
    public Instant getDeletedAt(){return deletedAt;} public void setDeletedAt(Instant value){deletedAt=value;}
    public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant value){createdAt=value;}
    public Instant getUpdatedAt(){return updatedAt;} public void setUpdatedAt(Instant value){updatedAt=value;}
}

package io.fluxora.platform.upstream.credential;

import java.time.Instant;

/**
 * 上游访问凭证持久化实体。
 * 密文、随机向量与去重指纹仅允许服务层和 Mapper 使用，绝不可从 Controller 返回。
 */
public class ProviderCredential {
    private Long id;
    private Long tenantId;
    private Long providerChannelId;
    private String name;
    private String credentialType;
    /** 上游认证注入方式；仅作为元数据下发，不包含认证材料。 */
    private String authType;
    /** 面向管理界面的安全脱敏展示值。 */
    private String maskedValue;
    /** HMAC 指纹只用于当前租户去重，不可返回至接口。 */
    private String credentialFingerprint;
    private String ciphertext;
    private String initializationVector;
    private String encryptionVersion;
    private boolean enabled;
    private int priority;
    private int weight;
    private String remark;
    private Instant deletedAt;
    private Instant createdAt;
    private Instant updatedAt;
    public Long getId(){return id;} public void setId(Long value){id=value;}
    public Long getTenantId(){return tenantId;} public void setTenantId(Long value){tenantId=value;}
    public Long getProviderChannelId(){return providerChannelId;} public void setProviderChannelId(Long value){providerChannelId=value;}
    public String getName(){return name;} public void setName(String value){name=value;}
    public String getCredentialType(){return credentialType;} public void setCredentialType(String value){credentialType=value;}
    public String getAuthType(){return authType;} public void setAuthType(String value){authType=value;}
    public String getMaskedValue(){return maskedValue;} public void setMaskedValue(String value){maskedValue=value;}
    public String getCredentialFingerprint(){return credentialFingerprint;} public void setCredentialFingerprint(String value){credentialFingerprint=value;}
    public String getCiphertext(){return ciphertext;} public void setCiphertext(String value){ciphertext=value;}
    public String getInitializationVector(){return initializationVector;} public void setInitializationVector(String value){initializationVector=value;}
    public String getEncryptionVersion(){return encryptionVersion;} public void setEncryptionVersion(String value){encryptionVersion=value;}
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean value){enabled=value;}
    public int getPriority(){return priority;} public void setPriority(int value){priority=value;}
    public int getWeight(){return weight;} public void setWeight(int value){weight=value;}
    public String getRemark(){return remark;} public void setRemark(String value){remark=value;}
    public Instant getDeletedAt(){return deletedAt;} public void setDeletedAt(Instant value){deletedAt=value;}
    public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant value){createdAt=value;}
    public Instant getUpdatedAt(){return updatedAt;} public void setUpdatedAt(Instant value){updatedAt=value;}
}

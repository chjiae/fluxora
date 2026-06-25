package io.fluxora.platform.runtime.availability;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 上游运行时故障与状态 Mapper；所有 SQL 位于 XML，禁止 Java 注解 SQL。 */
@Mapper
public interface UpstreamRuntimeFailureMapper {
    int insertEvent(RuntimeFailurePayload payload);
    void upsertState(RuntimeResourceStateUpdate update);
    void recoverState(@Param("scopeType") String scopeType, @Param("scopeKey") String scopeKey);
    Optional<Long> findCredentialTenantId(@Param("credentialId") Long credentialId);
    /** 查询所有非 AVAILABLE 的运行时状态，联表取资源名称。tenantId 为 null 时查全部租户（平台管理员）。 */
    List<RuntimeStateRow> findAllNonAvailableStates(@Param("tenantId") Long tenantId);
    /** 查询某个运行时状态的租户 ID，用于 outbox 写入时关联租户。 */
    Optional<Long> findTenantIdByScope(@Param("scopeType") String scopeType, @Param("scopeKey") String scopeKey);
}

package io.fluxora.platform.runtime.availability;

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
}

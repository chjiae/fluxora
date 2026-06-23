# Runtime Config Gateway Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不访问 PostgreSQL、不转发上游且不下发凭证的条件下，实现控制面运行时快照投影和 Gateway 本地鉴权、租户模型路由解析。

**Architecture:** 控制面写操作与 `runtime_outbox` 同事务提交；Projector 将 ImpactResolver 给出的最小 Scope 写成不可变 Redis 快照并原子推进 Manifest。Gateway 通过 Caffeine 的异步单飞加载 Redis Manifest/快照，校验 API Key、用户、租户，再从完整的 `TENANT_MODEL_ROUTE` 包选择内部 Target。

**Tech Stack:** Java 25、Spring Boot 4.1、MyBatis XML、PostgreSQL/Flyway、Spring Data Redis、Vert.x 5.1、Caffeine 3.2、Testcontainers、Vitest、Playwright。

---

## 文件地图

- 新增 `fluxora-platform/src/main/java/io/fluxora/platform/runtime/**`：Outbox、ImpactResolver、快照构建、Redis 访问、Projector、时钟扫描与运行时指标。
- 新增 `fluxora-platform/src/main/resources/mapper/RuntimeMapper.xml`：Outbox 领取、版本分配、Scope 反查与完整快照 SQL；所有新增 SQL 仅放此 XML。
- 新增 `fluxora-platform/src/main/resources/db/migration/V12__运行时快照与凭证池关联.sql`：运行时表、API Key lookup、凭证绑定模型、索引与完整中文元数据。
- 修改控制面服务和 Mapper 接口：业务写入后记录来源 mutation，不在业务服务写 Redis 或拼 Redis Key。
- 新增 `fluxora-gateway/src/main/java/io/fluxora/gateway/runtime/**`：安全快照 DTO、Redis 客户端、Caffeine L1、鉴权、路由选择、Pub/Sub 订阅和 HTTP 入口。
- 修改 `fluxora-gateway/pom.xml`、`FluxoraGatewayApplication.java`、`HealthHttpServer.java`：配置、依赖和实际请求入口。
- 新增 Platform/Gateway 测试；修改旧的 API Key、凭证、Playwright 测试以验证最终领域模型而非已删除的直连凭证结构。

### Task 1: 数据库最终模型与 API Key Lookup

**Files:**
- Create: `fluxora-platform/src/main/resources/db/migration/V12__运行时快照与凭证池关联.sql`
- Modify: `fluxora-platform/src/main/java/io/fluxora/platform/apikey/ApiKey.java`
- Modify: `fluxora-platform/src/main/java/io/fluxora/platform/apikey/ApiKeyHashingService.java`
- Modify: `fluxora-platform/src/main/resources/mapper/ApiKeyMapper.xml`
- Modify: `fluxora-platform/src/main/java/io/fluxora/platform/apikey/mapper/ApiKeyMapper.java`
- Test: `fluxora-platform/src/test/java/io/fluxora/platform/apikey/ApiKeyIntegrationTest.java`

- [ ] 写失败断言：创建 Key 后 `lookup_hash` 为 64 位 HMAC、绝不等于明文，旧 Key 未携带 Lookup 时不会被 Gateway Scope 投影。
- [ ] 在 Flyway 中创建 `runtime_outbox`、`runtime_snapshot_version`、`runtime_projection_state`、`provider_channel_credential`；迁移旧凭证绑定并删除 `provider_credential.provider_channel_id`；对新表及关键列写完整中文注释、`COMMENT ON TABLE/COLUMN` 和部分唯一索引。
- [ ] 将 API Key 实体从 `keyHash` 收敛为 `lookupHash`/`lookupHashVersion`；创建流程对 canonical plaintext 调用 `lookupHash`，仅将摘要入库。
- [ ] 运行 `mvn -pl fluxora-platform -Dtest=ApiKeyIntegrationTest,FlywayMigrationTest test`，预期所有旧 API Key 安全断言和迁移断言通过。

### Task 2: Outbox、影响解析与快照 Projector

**Files:**
- Create: `fluxora-platform/src/main/java/io/fluxora/platform/runtime/RuntimeOutboxService.java`
- Create: `fluxora-platform/src/main/java/io/fluxora/platform/runtime/RuntimeImpactResolver.java`
- Create: `fluxora-platform/src/main/java/io/fluxora/platform/runtime/RuntimeProjector.java`
- Create: `fluxora-platform/src/main/java/io/fluxora/platform/runtime/RuntimeRedisSnapshotStore.java`
- Create: `fluxora-platform/src/main/java/io/fluxora/platform/runtime/RuntimeTimeStateScanner.java`
- Create: `fluxora-platform/src/main/java/io/fluxora/platform/runtime/RuntimeSnapshotRebuildService.java`
- Create: `fluxora-platform/src/main/java/io/fluxora/platform/runtime/mapper/RuntimeMapper.java`
- Create: `fluxora-platform/src/main/resources/mapper/RuntimeMapper.xml`
- Modify: `fluxora-platform/src/main/java/io/fluxora/platform/FluxoraPlatformApplication.java`
- Modify: `fluxora-platform/src/main/resources/application.yml`
- Test: `fluxora-platform/src/test/java/io/fluxora/platform/runtime/RuntimeProjectorIntegrationTest.java`

- [ ] 写失败测试：相同 Scope 的旧版本无法覆盖高版本 Manifest，Redis 快照写失败不发布通知，通知失败使 Outbox 可重试。
- [ ] 实现 `recordMutation(tenantId, aggregateType, aggregateId, mutationType)`，通过同一 Spring 事务写入 Outbox；实现 `claimDueBatch` 的 `FOR UPDATE SKIP LOCKED` 和指数退避更新。
- [ ] 实现 Resolver 的 `AUTH_API_KEY`、`AUTH_USER`、`AUTH_TENANT`、`TENANT_MODEL_ROUTE` 最小 Scope 计算及反向关系批量查询；所有集合查找都使用单条 `IN`/JOIN SQL。
- [ ] Projector 写不可变 JSON 快照、校验 schema、用 Lua 比较更新 Manifest、成功后发布 `fluxora:runtime:v1:invalidate`；启动检查 namespace marker，缺失时创建可靠全量重建任务。
- [ ] 运行 `mvn -pl fluxora-platform -Dtest=RuntimeProjectorIntegrationTest test`，预期 Outbox 幂等、Redis 重建与敏感字段排除断言通过。

### Task 3: 接入所有控制面写操作

**Files:**
- Modify: `ApiKeyService.java`、`MemberService.java`、`TenantService.java`、`TenantController.java`
- Modify: `ProviderService.java`、`ProviderChannelService.java`、`ProviderCredentialService.java`
- Modify: `TenantModelService.java`、`TenantModelPriceService.java`、`TenantModelCandidateMappingService.java`、`ModelRouteService.java`、`RouteTargetService.java`、`ProviderChannelModelService.java`
- Modify: 对应 Mapper XML/接口与凭证 DTO/服务，改为 `provider_channel_credential` 绑定查询。
- Test: `RuntimeImpactResolverTest.java`、`UpstreamIntegrationTest.java`、`TenantModelIntegrationTest.java`

- [ ] 写失败测试：每一种 API Key、用户、租户、模型、价格、映射、RouteTarget、候选、通道、凭证绑定的变更都只产生矩阵定义的 Scope。
- [ ] 每个业务服务仅记录来源 mutation；Scope、Redis Key、发布事件和 Gateway 缓存名均不得出现在业务服务。
- [ ] 把凭证列表/统计/查重/导入改为绑定表 JOIN，创建凭证时在同一事务创建凭证与绑定；保留现有安全 DTO，并支持通过绑定存在性构建 `hasUsableCredential`。
- [ ] 运行 `mvn -pl fluxora-platform -Dtest=UpstreamIntegrationTest,TenantModelIntegrationTest,RuntimeImpactResolverTest test`，预期最终租户隔离与凭证池模型通过。

### Task 4: Gateway 异步缓存、鉴权与路由选择

**Files:**
- Modify: `fluxora-gateway/pom.xml`
- Create: `fluxora-gateway/src/main/java/io/fluxora/gateway/runtime/GatewayRuntimeProperties.java`
- Create: `fluxora-gateway/src/main/java/io/fluxora/gateway/runtime/GatewayRuntimeRepository.java`
- Create: `fluxora-gateway/src/main/java/io/fluxora/gateway/runtime/GatewayRuntimeCaches.java`
- Create: `fluxora-gateway/src/main/java/io/fluxora/gateway/runtime/ApiKeyAuthenticator.java`
- Create: `fluxora-gateway/src/main/java/io/fluxora/gateway/runtime/TenantModelRouteResolver.java`
- Create: `fluxora-gateway/src/main/java/io/fluxora/gateway/runtime/RuntimeInvalidationSubscriber.java`
- Modify: `FluxoraGatewayApplication.java`, `HealthHttpServer.java`
- Test: `fluxora-gateway/src/test/java/io/fluxora/gateway/runtime/GatewayRuntimeIntegrationTest.java`

- [ ] 写失败测试：非法 Key 在格式检查后不访问 Redis；重复无效 Key 命中负缓存；缺失/不兼容快照失败关闭；租户同名模型绝不串路由。
- [ ] 配置 Caffeine 的 `apiKeyAuthL1`、`userAuthL1`、`tenantAuthL1`、`tenantModelRouteL1` 与短时负缓存；所有未命中读取用异步 cache future 合并，不调用 `get()`、`join()` 或 JDBC。
- [ ] 实现 Authorization Bearer/x-api-key 解析、一次 canonical HMAC、逐 Scope 状态与时间校验；仅用 `tenantId + inboundProtocol + modelCode` 查路由包。
- [ ] 实现 priority 最小组 + 正 weight 加权选择，正常请求不调用上游；HTTP 只返回安全中文业务结果，绝不含 Target/通道/Redis/版本细节。
- [ ] 实现 Pub/Sub 精确失效、重连清 L1、受限 Manifest 核对、硬 TTL 和 Redis 不可用时的安全失败关闭。
- [ ] 运行 `mvn -pl fluxora-gateway -Dtest=GatewayRuntimeIntegrationTest,HealthHttpServerTest test`，预期 L1 热路径、singleflight、事件乱序和多实例失效通过。

### Task 5: 测试基线、文档与验收

**Files:**
- Create: `docs/runtime-config-architecture.md`
- Create: `docs/runtime-refresh-coverage-matrix.md`
- Modify: `README.md`
- Modify: `fluxora-web/e2e/*.spec.ts` 与相关服务测试
- Test: 全量 Maven、Vitest、Playwright

- [ ] 修复 Playwright 的重复多项目执行、共享数据竞争和过期选择器；桌面流程只在 desktop project 执行，响应式只断言各自视口可达。
- [ ] 写覆盖矩阵，逐项列出源实体、变更、关系、最小 Scope、Pub/Sub、L1、时间触发与测试名称。
- [ ] 更新 README 与架构文档，解释 PostgreSQL 真相、Redis 派生层、Manifest/版本、恢复流程、启动方式、安全边界和明确未实现范围。
- [ ] 依次运行 `mvn test`、`npm test -- --run`、`npm run build`、`npx playwright test`；记录真实外部阻塞项。
- [ ] 使用具体文件暂存并按 Conventional Commits 中文阶段提交；每次提交前运行 `git diff --check`。

# 运行时刷新覆盖矩阵

所有行由业务事务中的 `RuntimeOutboxService` 记录，再由 `RuntimeImpactResolver` 与 `RuntimeProjector` 处理。Scope 切换成功后才发布 Pub/Sub；Gateway 只精准删除对应 L1。

| 源实体 | 操作/字段 | 最小 Scope | Gateway L1 | 时间触发 | 自动化覆盖 |
| --- | --- | --- | --- | --- | --- |
| API Key | 创建、启停、删除、过期时间 | `AUTH_API_KEY` | API Key + 负缓存 | 到期 | `ApiKeyIntegrationTest`、`RuntimeProjectionIntegrationTest`、`GatewayRuntimeBehaviorTest` |
| 用户 | 创建、启停、删除 | `AUTH_USER` | 用户 | 否 | `MemberManagementIntegrationTest` |
| 租户 | 创建、启停、删除、到期时间 | `AUTH_TENANT` | 租户 | 到期 | `TenantManagementIntegrationTest` |
| TenantModel | 创建、启停、删除、编码、能力 | 旧/新 `TENANT_MODEL_ROUTE` | 模型路由 | 否 | `TenantModelIntegrationTest` |
| TenantModelPrice | 发布、当前价切换、生效/失效时间、四项价格 | 该模型全部协议 `TENANT_MODEL_ROUTE` | 模型路由 | 是 | `TenantModelIntegrationTest` |
| 候选映射 | 创建、启停、删除 | 被引用的 `TENANT_MODEL_ROUTE` | 模型路由 | 否 | `TenantModelIntegrationTest` |
| ModelRoute | 创建、启停、删除、协议 | 对应 `TENANT_MODEL_ROUTE` | 模型路由 | 否 | `TenantModelIntegrationTest` |
| RouteTarget | 创建、启停、删除、priority、weight、映射 | 对应 `TENANT_MODEL_ROUTE` | 模型路由 | 否 | `TenantModelIntegrationTest`、`GatewayRuntimeBehaviorTest` |
| ProviderChannelModel | 创建、启停、删除、能力、上游模型 | 所有引用候选的 `TENANT_MODEL_ROUTE` | 模型路由 | 否 | `TenantModelIntegrationTest` |
| ProviderChannel | 创建、启停、删除、BaseUrl、超时 | 所有引用通道的 `TENANT_MODEL_ROUTE` | 模型路由 | 否 | `UpstreamIntegrationTest` |
| ProviderChannelCredential | 绑定、解绑、绑定启停 | 所有引用通道的 `TENANT_MODEL_ROUTE` | 模型路由 | 否 | `UpstreamIntegrationTest` |
| ProviderCredential | 创建、启停、删除、轮换、认证材料变更 | 所有关联通道的 `TENANT_MODEL_ROUTE` | 模型路由 | 否 | `UpstreamIntegrationTest` |
| Provider | 创建、启停、删除 | 实际引用其地址的 `TENANT_MODEL_ROUTE` | 模型路由 | 否 | `UpstreamIntegrationTest` |
| ProviderBaseUrl | 创建、启停、删除、URL、协议 | 实际引用该地址的 `TENANT_MODEL_ROUTE` | 模型路由 | 否 | `UpstreamIntegrationTest` |

维护规则：新增任何影响 Gateway 放行、价格或路由的字段时，必须同时修改此矩阵、Outbox 写入、`RuntimeImpactResolver`、Snapshot Builder、Gateway 校验和对应测试。不得以全租户清缓存替代关联 Scope 查询。

# 运行时配置架构

## 边界

PostgreSQL 是 Fluxora 控制面配置的唯一事实来源。Redis 是完全可重建的派生运行时层；Gateway 只连接 Redis，绝不配置 JDBC 或访问 Platform HTTP。Gateway 只从独立敏感 Scope 临时解密当前请求选中的凭证，绝不缓存明文。

本轮 Gateway 完成 API Key 鉴权、用户/租户状态校验、租户模型价格和路由解析、RouteTarget 选择，以及 OpenAI / Anthropic 同协议原生 HTTP 和 SSE 中继。不实现跨协议转换、重试、熔断、Token 统计或计费。

## Scope 与快照

| Scope | 逻辑键 | 内容 |
| --- | --- | --- |
| `AUTH_API_KEY` | `lookupHash` | Key ID、租户/用户 ID、启停、过期时间、HMAC 版本 |
| `AUTH_USER` | `tenantId:userId` | 用户状态 |
| `AUTH_TENANT` | `tenantId` | 租户状态、到期时间、结算币种 |
| `TENANT_MODEL_ROUTE` | `tenantId:protocol:base64url(modelCode)` | 模型能力、当前价格字符串、路由与全部 Target 可用性 |
| `UPSTREAM_CREDENTIAL` | `tenantId:credentialId` | Gateway 专用运行时密文、凭证版本、认证方式与状态 |

每个快照都有 `schemaVersion`、`runtimeVersion`、`generatedAt`、`sourceOutboxId`、`sourceChangedAt`。金额使用 `BigDecimal.toPlainString()` 写入 JSON，绝不使用浮点数。

`TENANT_MODEL_ROUTE` 是模型执行的最小一致性单元，因此 Gateway 从不分别拼接模型、价格、候选、通道或凭证池快照。Target 含 Gateway 执行所需的 Base URL、超时和已排序的有效凭证引用，但不含密文、明文或认证 Header；这些敏感材料只在 `UPSTREAM_CREDENTIAL` Scope 中存在。

## Redis 不可变版本与 Manifest

```text
fluxora:runtime:v1:snapshot:{scopeType}:{scopeKey}:v:{runtimeVersion}
fluxora:runtime:v1:manifest:{scopeType}:{scopeKey}
```

Projector 先从 PostgreSQL 读取一个 Scope 的完整状态，写入新 Snapshot，再以 Lua 仅允许更高 `runtimeVersion` 原子前移 Manifest，最后发布轻量 Pub/Sub 失效事件。`runtime_snapshot_version` 用 PostgreSQL UPSERT 分配单调版本；旧投影或重复投影不能令 Manifest 回退。旧快照暂不清理，绝不删除当前激活版本。

Pub/Sub 事件只包含 Scope、逻辑键、版本和必要租户/协议信息，不包含快照、价格、凭证或 API Key 明文。

## Outbox 与恢复

所有影响 Gateway 的控制面写操作与 `runtime_outbox` 在同一 PostgreSQL 事务中提交。`RuntimeImpactResolver` 根据聚合和关联关系计算最小 Scope；业务 Service 不得直接写 Redis 或发布消息。

Projector 以 `FOR UPDATE SKIP LOCKED` 领取任务，超时 `PROCESSING` 任务会重新调度，失败使用受限指数退避和安全失败摘要。Redis 命名空间健康标记缺失时，Platform 写入 `FULL_REBUILD` Outbox，由 PostgreSQL 重建全部有效 Scope。

时间扫描把 API Key 到期、租户到期、价格生效/失效写回 Outbox；Gateway 仍在每个请求即时比较过期时间，扫描延迟不能导致继续放行。

## Gateway L1 与失败关闭

Gateway 先对 `flx_<8>_<32>` API Key 做格式检查，再对完整 canonical Key 做一次 HMAC-SHA-256。L1 缓存分别保存 API Key、用户、租户、路由与运行时凭证密文；无效 Key 使用独立短 TTL 负缓存。Caffeine AsyncCache 合并同 Scope 的并发未命中，热路径不访问 PostgreSQL。

快照未命中时 Gateway 异步读取 Manifest 和 Snapshot。Manifest/快照版本不一致、schema 不兼容、快照缺失、Redis 故障或 L1 过期一律失败关闭。Pub/Sub 重连成功时清空本机 L1；漏消息由硬 TTL 与后续 Manifest 读取收敛。

不会进入普通路由快照、普通 L1 或 Gateway 日志的资料：API Key 明文、密码、邮箱、余额、ProviderCredential 明文、数据库凭证密文、指纹、加密主密钥、认证 Header、数据库连接信息。Gateway 专用敏感 Redis Scope 与短 TTL L1 可保存运行时重加密密文、IV 和版本，但绝不保存明文或密钥。

## 本地验证

1. 启动 PostgreSQL 与 Redis，设置相同的 `APIKEY_LOOKUP_SECRET` 给 Platform 和 Gateway。
2. 启动 Platform；它会投影 Outbox 到 Redis。Gateway 启动后订阅 `fluxora:runtime:v1:invalidation`。
3. 创建 API Key、用户、模型/价格/路由/Target 和至少一个有效凭证绑定。确认 `runtime_outbox` 变为 `COMPLETED`，Redis 中存在 Snapshot 与 Manifest。
4. 对 `POST /v1/chat/completions` 或 `POST /v1/messages` 提供 Fluxora API Key 与租户模型编码。鉴权和选路成功后，Gateway 从敏感 Scope 临时解密上游凭证并发起同协议请求；JSON 与 SSE 响应中的模型名均改回租户模型编码。
5. 停用 API Key、用户、租户、Target 或解绑唯一凭证后，下一请求会因失效通知或硬 TTL 被拒绝/判为模型不可用。

# 运行时配置快照与 Gateway 解析设计

## 目标

将 PostgreSQL 控制面变更可靠投影为 Redis 的版本化运行时快照，使 Gateway 仅凭 API Key HMAC、Redis 与本机 L1 缓存完成身份状态检查及租户模型内部路由选择。本轮不转发任何上游请求、不读取或解密凭证。

## 方案选择

1. 业务接口直接更新 Redis：延迟低，但数据库提交失败、Redis 短暂故障和多实例通知会产生不一致，排除。
2. 外部 CDC / 消息中间件：能力充足，但会引入当前仓库未具备的部署、运维和测试面，超出本轮。
3. 事务 Outbox + Platform Projector：业务写入与 Outbox 在同一 PostgreSQL 事务中提交；Projector 通过 `FOR UPDATE SKIP LOCKED` 领取、重试和投影。此方案复用 Spring、MyBatis、Redis 与 Testcontainers，作为本轮实现。

## 数据与版本

- `runtime_outbox` 记录来源实体、操作、租户、失败次数、下一次重试时间与处理时间。
- `runtime_snapshot_version` 按 `(scope_type, scope_key)` 以原子 UPSERT 分配严格递增的 `runtimeVersion`。
- 每个快照写入不可变 `fluxora:runtime:v1:snapshot:{scopeType}:{scopeKey}:v:{version}`；Manifest `fluxora:runtime:v1:manifest:{scopeType}:{scopeKey}` 仅能由 Lua 比较脚本推进到更高版本。
- Scope 为 `AUTH_API_KEY`、`AUTH_USER`、`AUTH_TENANT`、`TENANT_MODEL_ROUTE`。路由 Scope 一次携带有效价格、模型能力、路由、目标、候选、通道与“存在可用凭证绑定”状态，金额序列化为字符串。
- API Key 新增 `lookup_hash` 与 `lookup_hash_version`。新 Key 只保存 `HMAC-SHA-256(APIKEY_LOOKUP_SECRET, canonicalApiKey)`；旧的不可重建 Key 在迁移中停用，避免保留无法安全验证的旧链路。
- 凭证收敛为 `provider_credential` 加 `provider_channel_credential` 绑定表。Gateway 快照只携带 `hasUsableCredential` 和 `credentialPoolVersion`，绝不携带明文、密文、Base URL、认证头或密钥材料。

## 处理链路

控制面服务在其现有事务内写入通用 mutation Outbox，`RuntimeImpactResolver` 独立计算最小 Scope。Projector 构建快照、写 Redis、原子切 Manifest，再发布轻量失效事件。通知失败保留 Outbox 重试；重复投影、乱序投影与重复通知均由版本比较保持幂等。

Gateway 在请求格式预校验后仅计算一次 HMAC。L1 命中不访问 Redis；未命中由 Caffeine 异步缓存合并单 Scope 的并发读取。API Key、用户、租户依次校验后，用 `(tenantId, inboundProtocol, modelCode)` 取得路由包，在最小 priority 组内按正权重抽样选择内部 Target。无快照、版本异常和 Redis 不可读均失败关闭。

## 失效与恢复

Pub/Sub 只传 Scope 与新版本；Gateway 仅在本机版本低于事件版本时精确失效。硬 TTL、有限周期 Manifest 校验、订阅重连清 L1 与 Redis namespace 健康标记共同处理漏消息和 Redis 重启。Platform 检测到命名空间缺失时写入全量重建 Outbox；时间扫描器为 API Key/租户/价格的时间边界写入幂等刷新事件，同时 Gateway 每次仍比较到期时间。

## 测试边界

Platform 使用 PostgreSQL + Redis Testcontainers 覆盖 Outbox、版本、Scope 影响、泄露边界和重建。Gateway 以嵌入式 Redis 集成测试和可替换运行时存储覆盖 L1、负缓存、singleflight、失效、隔离与加权选择。Playwright 先修复现有的跨项目并发与选择器问题，再增加关键控制面变更与 Gateway 安全响应验证。

# 上游分层调度、安全重试与运行时可用性

本文说明 Gateway 在高并发中继请求中的上游选择、无感重试、运行时故障隔离与计费衔接边界。

## 调度层级

每次客户端请求只有一个 `requestId`，每次内部上游尝试拥有独立 `attemptId` 与 `dispatchLeaseId`。Gateway 每个 Attempt 都重新调度：

1. 读取 `TENANT_MODEL_ROUTE` Redis Snapshot。
2. 只在最高可用 `priority` Tier 内选择候选；低优先级 Tier 仅在高优先级全部不可调度时兜底。
3. 按 `providerChannelId` 分组，避免同一 Channel 因 RouteTarget 数量更多而放大流量。
4. 在选中 Channel 内按 `quotaScope`、`billingAccountGroup` 与 Credential 做加权最少活跃流。
5. 使用 Redis 调度租约占用 Channel / quotaScope / Credential，并在完成、失败、取消或切换前幂等释放。

未显式配置共享关系时：

- `quotaScope = credential:{credentialId}`
- `billingAccountGroup = credential:{credentialId}`
- `trafficWeight = 1`
- `maxConcurrentStreams = 2147483647`

这只能做到单 Key 近似均衡；如果多个 Key 共享同一个上游账户或限流池，必须配置相同的 `billingAccountGroup` / `quotaScope`。

## Redis 调度租约

Gateway 使用 `RedisDispatchLeaseManager` 通过 Lua 原子完成：

- 清理过期租约；
- 检查 Credential 硬并发上限；
- 写入 Channel、quotaScope、Credential 三组 ZSET；
- 生成 `dispatchLeaseId`。

成员为 `attemptId`，分值为租约过期时间。Gateway 崩溃后容量由 TTL 和后续清理恢复。

默认降级策略为 `STRICT`：Redis 租约不可用时不伪造全局并发保护。`BEST_EFFORT` 仅适合没有硬并发限制的本地或低风险环境，并且只能提供单实例近似均衡。

## 安全重试

失败处理路径固定为：

```text
AttemptFailure
→ FailureClassifierRegistry
→ RuntimeIncidentMapper
→ LocalRuntimeQuarantine + RuntimeFailureReporter
→ DefaultRetryPolicy
→ DispatchExclusions
→ 下一次 UpstreamDispatchPlanner
```

`FailureClassifier` 只识别失败；`RetryPolicy` 只返回纯决策；`UpstreamDispatchPlanner` 只选择资源；`RelayAttemptCoordinator` 是唯一编排入口。

默认策略是 `SAFE_ONLY`：

- 请求未写出、连接前失败、明确无效 Credential、429、明确模型映射错误等 `NOT_EXECUTED` / `PRE_EXECUTION_REJECTED` 才允许无感重试。
- 请求已经完整写出且无法确认未执行、出现 usage、response id、Anthropic `message_start`、OpenAI 有效 chunk 或客户端已提交后，不再切换上游。
- `POSSIBLY_EXECUTED` 进入待对账失败路径，禁止重复执行和重复扣费。

客户端只看到 Fluxora 稳定中文错误；不会暴露上游名称、BaseUrl、Credential、RouteTarget、内部重试次数、失败分类或上游原始错误正文。

## 响应提交屏障

`ResponseCommitBarrier` 负责判断“是否还能重试”，不决定重试策略。

需要记住两个事实：

- `clientCommitState = NOT_COMMITTED` 不等于 `upstreamExecutionState = NOT_EXECUTED`。
- 上游可能已经执行但 Gateway 还没有向客户端写任何字节。

流式响应一旦向客户端写出任何内容，Gateway 禁止拼接备用上游响应，禁止伪造成功结束，后续只能按协议发送安全流中错误或关闭连接。

## 运行时故障隔离

Gateway 识别明确上游故障后：

1. 当前 `requestId` 立即追加排除条件。
2. 当前 Gateway 建立短 TTL 本地隔离，缩短传播窗口。
3. 通过既有 Redis Stream 投递 `UPSTREAM_RUNTIME_FAILURE_DETECTED`。
4. Platform 幂等写入 `upstream_runtime_failure_event` 与 `upstream_runtime_resource_state`。
5. Platform 写 `runtime_outbox`，Projector 发布新 Snapshot + Manifest，并通过 Pub/Sub 精确失效 Gateway L1。

本地隔离不是最终事实来源；长期状态以 Platform 投影后的 Redis Snapshot 为准。

运行时事件只包含资源 ID、Scope、失败类型、HTTP 状态、Retry-After 与执行确定性，不包含 API Key、上游 Key、BaseUrl、请求正文、响应正文或异常栈。

## 模型目录联动

`GET /v1/models` 仍只读取 `TENANT_MODEL_CATALOG` Snapshot，不访问 PostgreSQL、不调用上游 `/models`、不产生用量或费用。

长期不可用状态（如 `AUTH_FAILED`、`BILLING_EXHAUSTED`、`MODEL_MAPPING_INVALID`、`PERMISSION_DENIED`）会在下一版目录快照中隐藏不可调用模型。短暂状态（如 `RATE_LIMITED`、`QUARANTINED`、并发满）不让模型目录频繁闪烁。

## 计费衔接

一个客户端请求只创建一笔预冻结：

- 明确 `PRE_EXECUTION_REJECTED` 的失败 Attempt 不额外冻结、不结算、不收费。
- 最终成功只按最终成功 Attempt 的 usage 结算。
- `POSSIBLY_EXECUTED` 的失败不自动重试，保留既有冻结并进入待对账。
- 所有 Attempt 都未执行或预执行拒绝时，按既有未派发失败释放预冻结。

## 管理端

凭证管理页面展示：

- 静态状态与运行时状态；
- 最近失败分类、冷却结束时间；
- `billingAccountGroup`、`quotaScope`、`trafficWeight`、`maxConcurrentStreams`；
- 关联 Channel 数量；
- 平台管理员手动恢复 Credential 运行时状态入口。

页面和接口都不展示 Key、BaseUrl 或上游原始错误。

## 回归方式

默认自动化测试不依赖真实上游 Key。真实 DeepSeek 或其他上游回归只能通过未提交环境变量启用，严禁把真实 Key 写入源码、测试、文档、日志、Redis、数据库或 Git 历史。

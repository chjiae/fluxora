# 请求观测架构

Gateway 在 API Key 鉴权与 TenantModel 路由成功后生成随机 UUID `requestId`，并向 `fluxora:relay-events:v1` 写入 STARTED、FINISHED、FAILED 或 CANCELLED 事件。事件只含租户/用户/API Key 的内部 ID、协议、模型编码、路由引用、状态、耗时、四类 Token 与价格快照；不含 API Key、Authorization、凭证、BaseUrl、上游模型、消息、工具参数或完整正文。

Gateway 使用 Vert.x 异步 XADD，不等待 Redis 响应后再发送用户响应。Redis 失败时事件进入进程内有界重试队列；队列满或超过重试次数会记录脱敏指标并丢弃观测事件。Redis 可用时语义是 At-Least-Once，Platform 以 `event_id` 收据和 `request_id` 终态保护实现幂等持久化。它不是 Exactly Once：Redis 不可用期间若 Gateway 进程崩溃，内存队列仍可能丢失，真实扣费阶段必须引入可恢复的强一致 Outbox/账务保障。

Platform Consumer Group 读取 Stream，在 PostgreSQL 事务内先写事件收据、再写日志；事务成功之后才 XACK。终态先于 STARTED 到达时会安全补建记录，STARTED 晚到不会覆盖终态。

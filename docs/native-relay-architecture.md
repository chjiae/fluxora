# 原生中继架构

## 范围

Fluxora 当前只支持同协议原生中继：

```text
POST /v1/chat/completions  OPENAI   -> OPENAI
POST /v1/messages          ANTHROPIC -> ANTHROPIC
```

不做协议转换、重试、故障切换、计费或用量统计。

## 请求路径

```text
客户端 API Key
  -> Gateway API Key / 用户 / 租户校验
  -> TENANT_MODEL_ROUTE 快照与 RouteTarget 选择
  -> UPSTREAM_CREDENTIAL 敏感快照读取
  -> 临时解密并注入上游认证头
  -> Vert.x 非阻塞 HTTP 中继
  -> 原生 JSON 或 SSE 响应
```

`RelayService` 是唯一协议分支入口；`OpenAiRelayHandler` 和 `AnthropicRelayHandler` 只负责该协议的模型字段、错误载荷和 SSE 事件改写。

## 凭证边界

普通 `TENANT_MODEL_ROUTE` 快照只含凭证 ID、版本、认证方式和状态，不含明文、数据库密文、IV、AAD 或密钥。

`UPSTREAM_CREDENTIAL:{tenantId}:{credentialId}` 是 Gateway 专用敏感 Scope。Platform 用独立的 `FLUXORA_RUNTIME_CREDENTIAL_KEY` 将数据库密文重加密后写入 Redis；Gateway 仅按本次请求临时解密，不缓存明文，也不访问 PostgreSQL。

认证方式由控制面凭证元数据配置：

- `BEARER`：`Authorization: Bearer <credential>`
- `X_API_KEY`：`x-api-key: <credential>`
- `NONE`：不注入认证头

Gateway 会移除客户端传入的 `Authorization`、`x-api-key`、`Host` 与 hop-by-hop Header，避免把 Fluxora API Key 或调用方第三方凭证转发给上游。

## 流式与错误

非流式 JSON 只改写响应中的 `model`。SSE 不聚合上游响应：按事件顺序转发、保留 OpenAI `[DONE]` 与 Anthropic `event/data` 结构，并在任意 TCP 分块下改写模型字段。

- OpenAI SSE 的顶层 `model` 改为租户模型编码。
- Anthropic `message_start.message.model` 改为租户模型编码。
- 下游写队列饱和时暂停上游响应，drain 后恢复；客户端断连会取消上游请求。
- 上游在流开始后失败时写入该协议的安全 SSE 错误事件并结束流。

所有 Gateway 拒绝、快照缺失、解密失败、连接失败、超时和上游非 2xx 都返回当前入站协议的安全错误格式，不返回上游正文、URL、凭证、异常或内部状态码。

## URL 与请求边界

上游地址只由受控 Base URL 和固定协议端点用 `URI` 组装，禁止客户端传入完整上游 URL、控制 Host 或跟随重定向。

生产环境拒绝回环、链路本地、私网、多播、`.local` 和 `.internal` 地址。`local` / `test` Profile 仅额外允许 `localhost`、`127.0.0.1`、`::1` 与 `host.docker.internal`，不放宽任意私网地址。请求体必须是 JSON 对象、包含非空 `model`，且不超过 `GATEWAY_MAX_REQUEST_BODY_BYTES`。

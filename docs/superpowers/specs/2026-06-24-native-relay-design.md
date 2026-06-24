# Fluxora Native Relay Design

## Goal

在不让 Gateway 连接 PostgreSQL、Platform HTTP 或控制面密钥的前提下，支持 OpenAI 与 Anthropic 的同协议原生中继、非流式响应与 SSE 流式响应。

## Selected Approach

普通 `TENANT_MODEL_ROUTE` 快照继续只保存选路和执行元数据。新增独立 `UPSTREAM_CREDENTIAL` Scope，由 Platform 从数据库读取原有 AES-GCM 密文后，使用仅供运行时使用的独立 AES-256-GCM 密钥重新加密，再写入 Redis 不可变快照。

Gateway 只缓存运行时密文，不缓存明文。每个请求按稳定顺序选取 RouteTarget 的第一个有效 `credentialRef`，校验版本和状态后异步读取、临时解密、注入上游请求；请求结束、失败或取消后不保留明文引用。

## Module Boundaries

- Platform runtime: 扩展 Scope、Outbox 影响分析、MyBatis 快照查询和 Redis 投影；普通快照不含密文，敏感 Scope 不对 Web 或普通接口暴露。
- Gateway credential: `RuntimeCredentialResolver` 只读取 Redis 运行时密文并在请求作用域内解密。
- Gateway relay: `RelayService` 负责鉴权、选路、协议匹配和 Handler 分派；只在一个 `switch` 中区分协议。
- Gateway handlers: OpenAI 和 Anthropic Handler 分别处理 model 改写、允许的协议头、原生响应/SSE model 回写和安全错误。
- Gateway transport: `UpstreamHttpClient` 只负责 Vert.x 非阻塞请求、超时、背压、客户端断连取消与资源释放。

## Security Rules

- 客户端 `Authorization`、`x-api-key`、Host 和 hop-by-hop Header 永不转发。
- Base URL 来自受控运行时快照，使用 URI 组合固定端点；生产继续拒绝回环、内网、链路本地、元数据与未验证重定向目标。
- `local` / `test` profile 才允许 `127.0.0.1`、`::1`、`host.docker.internal`。
- 所有失败以当前入站协议的安全原生错误格式返回，不返回上游正文、URL、模型、凭证、Redis 或异常信息。

## Data Flow

```text
client request
  -> GatewayAuthenticator
  -> GatewayRouteResolver
  -> protocol equality check
  -> RuntimeCredentialResolver
  -> RelayHandler (replace tenant model code with upstream model id)
  -> UpstreamHttpClient
  -> RelayHandler (replace upstream model id in response/SSE)
  -> client response
```

## Non-Goals

不实现跨协议转换、计费、重试、故障切换、熔断、健康检查、限流或公开路由调试页面。

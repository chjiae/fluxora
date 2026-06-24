# 本地 Ollama 上游验证

本地模拟上游监听 `127.0.0.1:11435`，同时提供 OpenAI 和 Anthropic 原生端点。Gateway 原生运行时使用 `FLUXORA_PROFILE=local`，可显式访问该回环地址。

## 上游探测

```powershell
curl.exe -sS http://127.0.0.1:11435/v1/models `
  -H "Authorization: Bearer <openai-upstream-key>"
```

OpenAI 上游请求使用 `Authorization: Bearer <openai-upstream-key>`；Anthropic 上游请求使用 `x-api-key: <anthropic-upstream-key>` 和 `anthropic-version`。

## 控制面配置

为同一租户创建两条 Base URL 与 Channel：

| 入站协议 | Base URL | 凭证认证方式 | 上游端点 |
| --- | --- | --- | --- |
| `OPENAI` | `http://127.0.0.1:11435` | `BEARER` | `/v1/chat/completions` |
| `ANTHROPIC` | `http://127.0.0.1:11435` | `X_API_KEY` | `/v1/messages` |

每条 Channel 创建对应 `ProviderChannelModel`，再创建 TenantModel、价格、候选映射、同协议 Route 和 RouteTarget，最后启用 TenantModel。Platform Projector 会将路由和独立敏感凭证快照写到 Redis。

Platform 与 Gateway 必须使用相同的 `REDIS_*`、`APIKEY_LOOKUP_SECRET` 和 `FLUXORA_RUNTIME_CREDENTIAL_KEY`。若本地 Redis 启用密码，两个进程都必须配置 `REDIS_PASSWORD`。

## Gateway 流式验证

```powershell
$gatewayKey = '<Fluxora API Key>'
$openAiModel = '<tenant-openai-model-code>'
$anthropicModel = '<tenant-anthropic-model-code>'

curl.exe -N http://127.0.0.1:8081/v1/chat/completions `
  -H "Content-Type: application/json" `
  -H "Authorization: Bearer $gatewayKey" `
  -d "{\"model\":\"$openAiModel\",\"messages\":[{\"role\":\"user\",\"content\":\"只回复 OK\"}],\"stream\":true,\"max_tokens\":32}"

curl.exe -N http://127.0.0.1:8081/v1/messages `
  -H "Content-Type: application/json" `
  -H "x-api-key: $gatewayKey" `
  -H "anthropic-version: 2023-06-01" `
  -d "{\"model\":\"$anthropicModel\",\"max_tokens\":32,\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"只回复 OK\"}]}"
```

两条响应都应为 `200 text/event-stream` 并包含 `OK`。OpenAI 返回 `[DONE]`；Anthropic 保持 `message_start` 到 `message_stop` 的事件序列。响应内只能出现租户模型编码，不能出现底层 Ollama 模型标识。

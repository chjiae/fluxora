# GET /v1/models 原生模型发现

本接口让 OpenAI 兼容客户端可以用标准 `GET /v1/models` 发现当前 API Key 所属租户可调用的模型列表。

## 对外契约

- 路径：`GET /v1/models`
- 鉴权：Fluxora API Key，规则与 `/v1/chat/completions` 相同。
- 响应形状兼容 OpenAI：

```json
{
  "object": "list",
  "data": [
    {
      "id": "deepseek-chat",
      "object": "model",
      "created": 1767225600,
      "owned_by": "fluxora"
    }
  ]
}
```

`id` 始终是 `TenantModel.modelCode`，不是上游模型 ID。客户端后续请求也必须使用这个租户对外模型编码。

## 数据边界

Gateway 不会向上游调用 `/models`，也不会暴露通道、BaseUrl、凭证、上游模型 ID、权重、优先级或价格版本。列表来自 Platform 生成的 `TENANT_MODEL_CATALOG` Redis 运行时快照。

模型会出现在列表中，需要同时满足：

- 租户、用户、API Key、租户模型均可用；
- `TenantModel` 已启用、未删除；
- 存在 OPENAI 入站路由；
- 路由和至少一个目标处于可调用状态；
- 上游通道、候选映射、凭证等运行时依赖均满足中继前置条件。

Gateway 的路由选择和模型目录共用同一套可调用性判断，避免“模型列表展示了但请求无法中继”的漂移。

## 缓存与刷新

Platform 在租户模型、价格、路由、路由目标、候选映射、通道、凭证、API Key、用户或租户变更后，会将受影响的目录 scope 写入运行时刷新范围。Gateway 使用独立 L1 缓存读取目录快照；Redis Manifest 版本切换后会按 scope 失效。

## 不包含的能力

- 不代理或同步上游 `/models`。
- 不返回上游模型真实名称。
- 不支持匿名访问。
- 不生成请求日志、不预冻结余额、不写入计费流水。

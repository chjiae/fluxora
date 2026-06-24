# Token 用量与理论金额预览

统一 Token 桶为 `inputTokens`、`outputTokens`、`cacheWriteTokens`、`cacheReadTokens`。OpenAI 的 `prompt_tokens_details.cached_tokens` 会从普通 `prompt_tokens` 中扣除，避免缓存读取重复计入输入；未明确报告的缓存写入保持 `null`。Anthropic 直接读取 `input_tokens`、`output_tokens`、`cache_creation_input_tokens` 与 `cache_read_input_tokens`。SSE 不保存文本，只提取 OpenAI 最终 usage 或 Anthropic `message_start` / `message_delta` 的 usage。

`REPORTED` 表示当前价格需要的 Token 均已知；`PARTIAL` 表示存在缺失桶；`UNKNOWN` 表示未收到可靠 usage；`NOT_APPLICABLE` 表示未进入可计量上游调用。未知绝不写为 0。

价格在请求开始时从已验证的运行时路由快照固定，Platform 不会回查当前价格。金额以 CNY 八位原子精度计算：四条 `Token × 每百万单价` 分子先相加，最后只取整一次。只有 `CALCULATED` 记录进入理论金额汇总；理论金额不代表扣费。本轮不会修改钱包、余额、额度流水、卡密、账单、结算或退款。

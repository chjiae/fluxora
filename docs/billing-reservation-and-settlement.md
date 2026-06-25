# 余额预冻结与请求结算

Fluxora 的 API 请求计费分为同步预冻结和异步最终结算两段。目标是确保 Gateway 在真实投递前能够阻止明显余额不足的请求，同时让最终扣费以 Redis Stream 中的安全请求事件和上游用量为准。

## 请求生命周期

1. 客户端调用 Gateway。
2. Gateway 鉴权、读取路由快照、计算保守预冻结金额。
3. Gateway 使用内部 HMAC 调用 Platform `/internal/gateway/billing/reservations`。
4. Platform 原子地将 `user_credit_account.balance` 转入 `frozen_balance`，写入 `billing_reservation` 和 `credit_transaction(RESERVE)`。
5. 预冻结成功后 Gateway 才向上游投递。
6. Gateway 结束请求后写入 Redis Stream 安全观测事件。
7. Platform Consumer Group 幂等消费事件，落库请求日志并根据用量做最终结算、释放或转待对账。

## 预冻结金额

Gateway 使用运行时快照中的价格与 token 上限做保守估算：

- 输入 token：按原始请求体 UTF-8 字节数作为保守上限；
- 输出 token：优先使用请求中的 `max_tokens` / `max_completion_tokens`，没有则使用租户模型默认输出上限；
- 缓存读写：如果配置了缓存价格，必须存在对应缓存 token 上限，否则拒绝请求；
- 金额只在最终合计时按 `CnyPrecisionPolicy` 舍入，避免中间多次 rounding。

当请求超过租户模型 token 上限、缺少必要上限或价格无法安全计算时，Gateway 返回安全错误，不向上游投递。

## 结算规则

- `NOT_DISPATCHED`：上游未投递，全部释放冻结金额。
- 用量完整且实际金额小于等于预冻结金额：扣减实际金额，释放差额，状态为 `SETTLED`。
- 用量未知、部分未知、价格不可用、投递状态不确定：进入 `RECONCILIATION_PENDING`，不自动扣减或释放。
- 实际金额大于预冻结金额：进入 `RECONCILIATION_PENDING`，不自动追扣，保留差额供人工处理。
- 预冻结后长时间没有终态事件：定时扫描转入 `RECONCILIATION_PENDING`，避免冻结永久悬挂。

所有余额变更均在数据库事务中完成，并写入不可变额度流水：`RESERVE`、`SETTLE`、`RELEASE`。

## 安全边界

- Gateway 到 Platform 的内部计费接口使用 HMAC 签名、时间戳和 requestId。
- 请求日志和结算表不保存 API Key、请求正文、工具参数、完整响应、BaseUrl、凭证或上游模型 ID。
- 用户可见错误只给出可操作中文说明，不泄露内部异常、SQL、堆栈或密钥信息。

## 明确不做

- 不支持余额为负。
- 不做自动退款到第三方支付渠道。
- 不在超出预冻结金额时自动追扣。
- 不把未知用量按 0 计费。
- 不自动完成人工对账。

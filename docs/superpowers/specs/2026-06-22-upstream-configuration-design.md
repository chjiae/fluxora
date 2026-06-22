# Fluxora 上游配置控制面设计

## 目标与边界

本阶段在既有用户、RBAC、租户、统一异常、错误提示、主题和控制台布局之上，实现上游配置控制面：`Provider`、`ProviderBaseUrl`、`ProviderChannel` 与 `ProviderCredential`。

实现范围仅包括真实 CRUD、权限与租户隔离、凭证可逆加密、凭证批量导入与重复处理、前端管理页面、数据库迁移和自动化验收。本阶段不实现模型、路由、网关读取配置、Redis 同步、上游连接测试、真实请求或计费。

现有前端主题、颜色、字体、Naive UI 组件体系和控制台布局保持不变；新增页面复用当前视觉与交互语言，不进行视觉重设计。

## 领域模型

关系固定为：

```text
Provider
└── ProviderBaseUrl
    └── ProviderChannel
        └── ProviderCredential[]
```

### Provider

Provider 表示上游厂商，而非 URL 或实际租户通道。范围为 `PLATFORM_SHARED` 或 `TENANT_PRIVATE`；共享 Provider 可供所有租户选用，私有 Provider 仅租户自身和平台管理员可见。状态为 `ENABLED`、`DISABLED`、`DELETED`，删除采用逻辑删除。

### ProviderBaseUrl

ProviderBaseUrl 表示一个 Provider 在指定协议下的逻辑基础入口。协议本轮为 `OPENAI`、`ANTHROPIC`。保存用户原始填写值与规范化值；同一 Provider 下 `(protocol, normalized_base_url)` 唯一，因此相同物理 URL 可因协议不同同时存在。

后端只接受 `http://` 或 `https://`，禁止 query 与 fragment，去除末尾多余 `/`，保留 `/v1`、`/anthropic` 等公共路径。具体业务接口路径（如 `/chat/completions`、`/messages`）在校验阶段拒绝，后续由网关拼接。

### ProviderChannel

Channel 是租户实际可使用的未来路由单元，始终归属于一个租户并引用一个 ProviderBaseUrl。保存名称、状态、优先级、权重、连接/读取超时和备注。创建或更新时验证其 BaseUrl 来自平台共享 Provider 或当前租户私有 Provider，且关联资源均处于启用状态。

### ProviderCredential

Credential 归属一个租户和一个 Channel。保存名称、类型（本轮 `API_KEY`）、脱敏展示、加密密文、IV、加密版本、HMAC 指纹、状态、优先级、权重、备注和逻辑删除时间。公开 DTO 永远不含明文、密文、IV、指纹或密钥版本细节。

## 加密与去重

- 使用 AES-256-GCM 可逆加密；主密钥由 `FLUXORA_CREDENTIAL_MASTER_KEY` 配置。
- 使用 HMAC-SHA-256 指纹进行重复检测；去重密钥由 `FLUXORA_CREDENTIAL_FINGERPRINT_KEY` 配置。
- 开发环境提供仅本地测试用默认值，环境变量可覆盖；生产 profile 不提供默认值，缺失或格式错误时安全拒绝启动且不泄露配置内容。
- 数据库使用 `(tenant_id, credential_fingerprint) WHERE deleted_at IS NULL` 部分唯一索引，确保同租户未删除凭证在并发场景下也只能写入一次。
- 明文只在创建、替换或导入请求处理期间存在；提交完成后前端立即清空输入 ref，后端不记录明文、密钥或原始异常。

## 访问控制与租户隔离

- `PLATFORM_ADMIN` 可管理共享 Provider/BaseUrl，查看并管理任何租户的私有配置、Channel、Credential，并可按租户筛选。
- `TENANT_ADMIN` 可管理当前租户私有 Provider/BaseUrl、当前租户 Channel/Credential，可只读并选用平台共享 Provider/BaseUrl；不可修改共享资源或访问其他租户数据。
- `TENANT_MEMBER` 没有任何上游配置读取或管理权限。
- 所有接口在服务层从当前身份推导或验证目标租户。租户管理员的客户端 tenantId 永不作为授权依据；详情、导入结果、异常和日志均不得泄露其他租户凭证信息。

## 批量导入

批量导入固定到当前 Channel，支持多行文本、TXT 和 CSV。服务端逐行清理首尾空白并保留原始大小写，不修改中间字符。单次数量限制由配置控制。

处理按以下集合化步骤执行：先在单批内按指纹去重，再通过一次 `IN` 查询取得当前租户所有已存在且未软删除的指纹，最后批量写入可导入项。唯一索引冲突被转换为“导入过程中已存在，已跳过”。已启用和已停用凭证都视为存在；已软删除凭证可重新导入。

响应仅返回行号、脱敏标识、结果和安全原因。汇总包括成功、跳过、格式无效、数量超限和并发重复数量；关闭结果界面、刷新或重新登录后不保留原文。

## 前端

控制台新增“上游配置”菜单分组，包含上游厂商、接入地址和上游通道；凭证在通道详情内管理。菜单只作为体验优化，后端权限仍为最终边界。

所有页面沿用现有 Naive UI、主题、字体、表格、抽屉、确认弹窗、状态标签与异步状态组件。Provider 详情内管理 BaseUrl；Channel 详情内分区展示通道信息与凭证。共享资源显示只读/受保护原因；危险操作二次确认。单凭证创建与替换使用密码输入；批量导入支持粘贴、TXT、CSV、统一参数和脱敏明细。

桌面、平板与移动端复用现有控制台响应式壳：移动端筛选折叠、表格隐藏次要列、详情通过抽屉查看完整信息。

## 数据库与接口

新增 Flyway 迁移创建四张业务表、外键、检查约束、检索索引、部分唯一索引与权限初始化。迁移必须同时包含中文 SQL 注释以及 `COMMENT ON TABLE` / `COMMENT ON COLUMN` 元数据说明。

所有 SQL 只存在于 MyBatis XML。接口使用类型化 DTO 和既有统一响应结构，资源前缀为 `/api/providers`、`/api/provider-base-urls`、`/api/provider-channels`、`/api/provider-credentials`。不使用无类型 `Map` 拼接响应。

## 验证

后端 Testcontainers 测试覆盖共享/私有隔离、URL 规范化、同 URL 多协议、加密/内部解密、无明文响应、替换凭证、软删除重导入、停用凭证重复、单批重复、并发唯一约束、引用保护和越权拒绝。

前端执行构建、单元测试和真实 Playwright：平台管理员创建共享 Provider/BaseUrl，租户管理员创建私有配置并选用共享 BaseUrl 创建 Channel，创建/导入凭证并验证脱敏、重复与软删除重导入；覆盖桌面、平板、手机和安全错误文案。

README 记录模型关系、权限边界、URL 规则、开发与生产密钥配置、导入与软删除规则、启动和手工测试步骤。

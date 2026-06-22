# Fluxora

Fluxora 是一个多租户 API 中转平台：控制面负责配置与运营，数据面负责高性能请求中继。当前阶段已实现用户体系、RBAC 权限、多租户与平台管理。

## Modules

- `fluxora-common`：通用响应、错误编码、异常契约。
- `fluxora-platform`：Spring Boot 4.1.0 控制面，包含认证、RBAC、租户管理 REST API；PostgreSQL + Flyway + MyBatis。
- `fluxora-gateway`：Vert.x 5.1.2 数据面，提供 `/health`；不依赖 Spring 或数据库组件。
- `fluxora-web`：Vue 3 + Vite 控制台与官网；Pinia 状态管理、Axios + Cookie 认证、路由守卫。

## Prerequisites

- JDK 25、Maven 3.9+、Node.js 22+
- 已在本地 Docker 中运行的 PostgreSQL（项目不会部署它）。

## 启动

```powershell
# 构建全部模块
mvn clean install -DskipTests

# 平台服务：http://localhost:8080/actuator/health
# 首次启动将在 PostgreSQL 中自动执行 Flyway 迁移，初始化六张核心表
mvn -pl fluxora-platform -am spring-boot:run

# 网关服务：http://localhost:8081/health
mvn -pl fluxora-gateway -am exec:java -Dexec.mainClass=io.fluxora.gateway.FluxoraGatewayApplication

# 前端：http://localhost:5173
cd fluxora-web
npm ci
npm run dev
```

复制 `.env.example` 为 `.env` 后，可覆盖 PostgreSQL、Redis、端口等本地参数。`docker compose up --build` 只启动 platform、gateway、web；基础组件继续使用现有本地 Docker 实例，默认经 `host.docker.internal` 访问。

## 初始账号

首次启动后，平台管理员自动初始化（幂等，不会重置已有密码）：

| 账号 | 密码 | 角色 |
| --- | --- | --- |
| `admin` | `Admin@2026!` | 平台管理员（PLATFORM_ADMIN） |

默认密码仅用于本地开发测试，生产环境请通过环境变量 `INIT_ADMIN_USERNAME`、`INIT_ADMIN_PASSWORD` 覆盖。

## 启动后流程

1. 浏览器打开 `http://localhost:5173/login`
2. 使用 `admin / Admin@2026!` 登录
3. 系统检测到自营租户未初始化，自动跳转初始化向导
4. 填写自营租户名称与管理员信息，点击创建
5. 自营租户创建完成后进入控制台，可管理租户（分页、搜索、筛选、新增、编辑、启停、过期设置、删除）

## API

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| POST | `/api/auth/login` | 登录，签发 HttpOnly JWT Cookie | 公开 |
| GET | `/api/auth/me` | 获取当前用户信息 | 登录 |
| POST | `/api/auth/logout` | 退出登录 | 登录 |
| GET | `/api/tenant/self-operated/status` | 自营租户初始化状态 | 登录 |
| POST | `/api/tenant/self-operated/initialize` | 初始化自营租户（幂等） | 登录 |
| GET | `/api/tenant` | 租户分页列表 | 平台管理员 |
| GET | `/api/tenant/{id}` | 租户详情 | 平台管理员 |
| POST | `/api/tenant` | 新增租户 | 平台管理员 |
| PUT | `/api/tenant/{id}` | 编辑租户信息 | 平台管理员 |
| PUT | `/api/tenant/{id}/toggle` | 启停租户 | 平台管理员 |
| DELETE | `/api/tenant/{id}` | 逻辑删除租户 | 平台管理员 |
| GET | `/api/tenant/{tenantId}/members` | 指定租户成员分页 | `MEMBER_READ`（平台管理员路径） |
| GET | `/api/members` | 当前租户成员分页 | `MEMBER_READ`（租户管理员路径） |
| GET | `/api/members/{id}` | 成员详情 | `MEMBER_READ` |
| POST | `/api/tenant/{tenantId}/members` | 创建成员 | `MEMBER_CREATE` |
| PUT | `/api/members/{id}` | 编辑成员基础资料 | `MEMBER_UPDATE` |
| PUT | `/api/members/{id}/role` | 调整成员角色 | `MEMBER_UPDATE` |
| PUT | `/api/members/{id}/enable` | 启用成员 | `MEMBER_ENABLE` |
| PUT | `/api/members/{id}/disable` | 停用成员 | `MEMBER_DISABLE` |
| DELETE | `/api/members/{id}` | 软删除成员 | `MEMBER_DELETE` |
| PUT | `/api/members/{id}/password` | 重置成员密码 | `MEMBER_PASSWORD_RESET` |
| GET | `/api/members/assignable-roles` | 当前操作者可分配的角色 | `MEMBER_READ` |
| GET | `/api/api-keys` | 当前用户 API Key 分页 | `API_KEY_SELF_MANAGE` |
| POST | `/api/api-keys` | 创建 API Key（完整 Key 仅本响应返回一次） | `API_KEY_SELF_MANAGE` |
| GET | `/api/api-keys/{id}` | Key 详情（脱敏） | `API_KEY_SELF_MANAGE` 或更高 |
| PUT | `/api/api-keys/{id}` | 编辑 Key 名称/过期时间 | 同上 |
| PUT | `/api/api-keys/{id}/enable` | 启用 Key | 同上 |
| PUT | `/api/api-keys/{id}/disable` | 停用 Key | 同上 |
| DELETE | `/api/api-keys/{id}` | 软删除 Key | 同上 |
| GET | `/api/tenant/{tenantId}/api-keys` | 指定租户 Key 分页 | `API_KEY_TENANT_MANAGE` |
| GET | `/api/admin/api-keys` | 跨租户 Key 分页 | `API_KEY_CROSS_TENANT_MANAGE` |
| GET | `/api/credit/me` | 当前用户额度账户 | `CREDIT_SELF_READ` |
| GET | `/api/credit/me/transactions` | 当前用户额度流水分页 | `CREDIT_SELF_READ` |
| POST | `/api/tenant/{tenantId}/credit/adjust` | 调整用户额度（原子 + 流水） | `CREDIT_TENANT_ADJUST` |
| GET | `/api/credit/adjustable-users` | 可调整额度用户列表 | `CREDIT_TENANT_ADJUST` |
| POST | `/api/cards/redeem` | 核销卡密充值到当前用户 | `CARD_SELF_REDEEM` |
| POST | `/api/tenant/{tenantId}/cards/batches` | 创建卡密批次（完整明文仅本响应返回一次） | `CARD_TENANT_MANAGE` / `CARD_CROSS_TENANT_MANAGE` |
| GET | `/api/tenant/{tenantId}/cards/batches` | 租户卡密批次分页 | 同上 |
| GET | `/api/tenant/{tenantId}/cards/batches/{batchId}` | 批次详情（含派生统计） | 同上 |
| PUT | `/api/tenant/{tenantId}/cards/batches/{batchId}/enable\|disable` | 启用 / 停用批次 | 同上 |
| GET | `/api/tenant/{tenantId}/cards/batches/{batchId}/cards` | 批次下单张卡密分页 | 同上 |
| PUT | `/api/cards/{cardId}/enable\|disable` | 启用 / 停用单张卡密（终态卡密拒绝） | 同上 |
| GET | `/api/admin/cards/batches` | 跨租户卡密批次分页 | `CARD_CROSS_TENANT_MANAGE` |

## 上游配置

### 模型关系

```text
Provider
├── ProviderBaseUrl
│   └── ProviderChannel
│       └── ProviderCredential[]
```

- Provider（上游厂商）：表示上游服务来源（OpenAI/Anthropic/DeepSeek 等），范围为 `PLATFORM_SHARED`（供全部租户选用）或 `TENANT_PRIVATE`（仅归属租户与平台管理员可见）。
- ProviderBaseUrl（接入基础地址）：绑定一家厂商与一种协议（`OPENAI`/`ANTHROPIC`），保存原始 URL 与规范化 URL；同一物理 URL 可按不同协议分别创建。
- ProviderChannel（上游通道）：租户实际可用的路由单元，引用一个 ProviderBaseUrl 并保存优先级/权重/超时等运行参数。
- ProviderCredential（上游凭证）：归属一个通道，保存 AES-256-GCM 密文、HMAC-SHA-256 去重指纹与脱敏展示；公开 DTO 绝不包含明文/密文/指纹/IV/加密版本。

### 权限边界

| 角色 | 权限 |
| --- | --- |
| `PLATFORM_ADMIN` | 管理共享 Provider/BaseUrl；跨租户查看/管理所有私有配置、通道与凭证；可批量导入 |
| `TENANT_ADMIN` | 管理本租户私有 Provider/BaseUrl；可读平台共享 Provider/BaseUrl 并创建通道；管理本租户通道与凭证；可批量导入 |
| `TENANT_MEMBER` | 不可访问上游配置页面与接口（无 `UPSTREAM_READ` 权限） |

写操作由服务层强制校验身份：租户管理员传入的客户端 `tenantId` 永不作为授权依据；私有资源归属租户由服务层推导。

### 接入基础地址 URL 规则

- 仅允许 `http://` 或 `https://`；去除末尾多余 `/`；禁止 query 与 fragment；保留 `/v1`、`/anthropic` 等公共路径。
- 拒绝 `/chat/completions`、`/messages` 等业务接口路径（后续网关负责拼接）。
- 同一提供商下 `(provider_id, protocol, normalized_base_url)` 唯一；相同物理 URL（如 `https://api.example.com/v1`）可在 `OPENAI` 与 `ANTHROPIC` 两个协议下分别创建。
- 规范化后的 URL 用于唯一性判断；用户填写的原始值同时保留以供审计。

### 凭证加密存储

- 使用 AES-256-GCM 可逆加密（12 字节随机 IV、128 位认证标签，固定版本 `AES256_GCM_V1`）。
- HMAC-SHA-256 指纹使用独立密钥，对 trim 后原文计算（大小写敏感），仅用于同租户未删除凭证的重复判断。
- 开发环境在 `application.yml` 中提供 Base64 编码的 32 字节测试密钥；生产通过 `FLUXORA_CREDENTIAL_MASTER_KEY` 与 `FLUXORA_CREDENTIAL_FINGERPRINT_KEY` 环境变量覆盖。
- 后端 GlobalExceptionHandler 统一兜底：启动时校验密钥长度与格式，异常消息不拼接配置原文。
- 公开 DTO 与前端状态绝不记录完整明文、密文、随机向量、去重指纹或加密版本；`deleted_at` 由 getStatus() 派生为 ENABLED/DISABLED 状态字段。

### 批量导入

- 固定导入到当前选定通道；支持多行文本粘贴、TXT 文件、CSV 文件。
- 逐行清理首尾空白，保留原始大小写；空行跳过。
- 批内指纹去重（保留首次出现行，后续标记同一批次内重复）；一次 IN 查询同租户未删除指纹；一次 `INSERT ... ON CONFLICT ... RETURNING` 批量写入。
- 已启用和已停用（但未软删）凭证均视为存在，跳过；已软删除凭证视为不存在，可重新导入。
- 单次导入数量上限由 `CREDENTIAL_IMPORT_MAX_COUNT` 配置（默认 1000）；超限行标记为限制跳过。
- 导入响应仅返回行号、脱敏标识、处理结果与安全原因；关闭结果页或刷新后不保留原文。
- 汇总包含成功、同一批次重复、当前租户已存在、格式无效、超限与并发重复数量。

### 开发密钥配置

`.env` 文件（从 `.env.example` 复制）中添加：
```env
# 上游凭证主密钥与去重密钥：使用 Base64 编码的 32 字节随机值
# 默认通过 application.yml 提供仅本地测试用值，生产必须覆盖
FLUXORA_CREDENTIAL_MASTER_KEY=
FLUXORA_CREDENTIAL_FINGERPRINT_KEY=
CREDENTIAL_IMPORT_MAX_COUNT=1000
```
生成测试密钥（PowerShell）：
```powershell
$bytes = new-object byte[] 32; (new-object Security.Cryptography.RNGCryptoServiceProvider).GetBytes($bytes); [Convert]::ToBase64String($bytes)
```

### 浏览器手工验收

按 `## 启动` 节启动 PostgreSQL、Redis、`fluxora-platform`、`fluxora-web` 后，可在浏览器走通以下流程：

1. 以 `admin / Admin@2026!` 登录平台。
2. 进入「上游厂商」，点击 **新增厂商**，创建一个平台共享 Provider（名称/编码/范围=平台共享）。
3. 进入「接入地址」，选择刚创建的共享厂商，点击 **新增地址**：
   - 先创建 `OPENAI + https://api.example.com/v1`；
   - 再创建 `ANTHROPIC + https://api.example.com/v1` —— 应成功（同 URL 不同协议）。
   - 再次尝试创建 `OPENAI + https://api.example.com/v1///` —— 应被安全拒绝（不出现 500/SQL/堆栈）。
4. 使用默认租户管理员 `e2eadmin / chen2983` 登录（或通过自营初始化创建）。
5. 进入「上游厂商」，可看到共享 Provider（只读）并创建本租户私有 Provider。
6. 进入「上游通道」，点击 **新增通道**：选择共享厂商 → 选择 OPENAI 接入地址 → 填写名称，创建通道。
7. 在通道列表点击通道名称，打开详情抽屉的「凭证」区域：
   - **新增凭证**：输入上游 API Key（密码框），保存后列表只显示脱敏值（`sk-***YZ`），不可查看明文。
   - **批量导入**：粘贴多行凭证，点击导入，查看汇总与逐行脱敏明细；再次导入相同凭证应全部跳过。
   - **替换凭证**：输入新凭证并确认，旧密文不再被引用。
8. 普通成员（无 `UPSTREAM_READ` 权限）看不到「上游配置」菜单，访问路由被守卫拦截。
9. 上下游配置页面在桌面（1440×900）、平板（768×1024）、移动端（390×844）视口下无横向溢出。
10. 所有失败 toast / dialog 不出现 HTTP 状态码、业务编码、SQL、堆栈或后端原始 message。



## 自营租户保护规则

`default` 自营租户（类型 `SELF_OPERATED`）受后端保护：

- **不可删除**：返回"自营租户受保护，无法删除"
- **不可停用**：返回"自营租户受保护，无法停用"
- **租户码不可变更**：创建后不允许修改租户码
- **类型不可变更**：不允许改为 `THIRD_PARTY`

## API Key 管理

### 归属与权限

API Key 归属链：`租户 → 租户用户 → API Key`。仅 `TENANT` 作用域用户可拥有 Key；平台用户不持有 Key 但被授权跨租户管理。

| 角色 | 自己 Key | 本租户 Key | 跨租户 Key |
|---|---|---|---|
| `PLATFORM_ADMIN` | — | — | ✅ |
| `TENANT_ADMIN` | ✅ | ✅ | — |
| `TENANT_MEMBER` | ✅ | — | — |

### 安全机制

1. **格式**：`flx_<8 字符可见前缀>_<32 字符密钥段>`，总 45 字符，URL-safe Base64 字符集。
2. **DB 中不保存明文**：仅存 `key_prefix` 与 `key_hash = HMAC-SHA256(secret_part, server_pepper)` 的 hex。
3. **Pepper 来源**：`fluxora.security.apikey.pepper`，由环境变量 `APIKEY_PEPPER` 覆盖；生产部署必须改为至少 32 字符随机字符串。Pepper 绝不进入日志、堆栈、响应或前端。
4. **一次性返回**：完整 plaintext 仅在 `POST /api/api-keys` 的响应中返回一次。后续列表、详情、刷新、重新登录均不再返回。前端 `ApiKeyRevealPanel` 强制非 closable 弹窗展示，关闭后立刻 nuke 内存 ref；不写入 localStorage / sessionStorage / URL。
5. **状态四态**：`ENABLED` / `DISABLED` / `EXPIRED` / `DELETED`，由 `enabled` + `expire_at` + `deleted_at` 派生。
6. **网关校验路径**（本轮预留）：按 `key_prefix` 索引查找 → 计算输入 secret 的 HMAC → 与 `key_hash` 常量时间比较。

## 用户额度

### 设计

- 每个 `TENANT` 用户对应一个 `user_credit_account`，由 `MemberService.createMember` 同事务创建；V5 迁移为已有用户幂等回填。
- `balance` 使用 `DECIMAL(20,4)`（16 整数位 + 4 小数位），`CHECK (balance >= 0)` 保证非负。
- 每次调整生成不可篡改的 `credit_transaction` 流水：仅 INSERT，无 update / delete 接口，无 `updated_at` 列。
- `direction ∈ {CREDIT, DEBIT}`、`delta > 0`、`reason` 必填（≤256 字符）。

### 并发与原子性

余额调整通过单语句 SQL 在 Postgres 中原子完成：

```sql
UPDATE user_credit_account
SET balance = balance + :delta, updated_at = NOW()
WHERE user_id = :userId AND balance + :delta >= 0
RETURNING balance - :delta AS balance_before,
          balance          AS balance_after;
```

- `WHERE balance + :delta >= 0` 同时表达「余额非负不变量」与「不变量失败 → 不更新（影响 0 行）」语义；service 收到 `null` 抛 `CREDIT_INSUFFICIENT`。
- 单 SQL 隐式持有行锁，并发 N 次 DEBIT 同时打到不足以全部扣减的余额，只会成功 `floor(balance / amount)` 次（集成测试已验证 10 线程并发扣减只有 7 次成功）。
- `RETURNING` 直接给出 `balance_before` / `balance_after`，与流水写入在同事务内完成；任一失败回滚。
- MyBatis 中用 `<select affectData="true">` 表达 UPDATE…RETURNING（3.5.12+ 支持），让 Spring 事务把它视为写。

### 角色权限

| 角色 | 自己额度 | 本租户额度（读 / 写） | 跨租户额度 |
|---|---|---|---|
| `PLATFORM_ADMIN` | — | — | ✅ 跨租户读 + 写 |
| `TENANT_ADMIN` | ✅ 读 | ✅ 读 + 写 | — |
| `TENANT_MEMBER` | ✅ 读 | — | — |

普通用户不能调整自己额度；任何角色调整必须填写原因；扣减额度时前端做二次确认（`dialog.warning`）。

### 浏览器手工验收（API Key + 额度）

1. 以 `admin / Admin@2026!` 登录 → 进入「租户管理」→ 任意租户「管理成员」→ 新建一个 `TENANT_MEMBER`（例如 `e2euser / Pwd2026Strong`）。
2. 退出，以新用户登录 → 侧栏出现「我的 API Key」「我的额度」。
3. 进入「我的 API Key」→ 新建 Key → **一次性弹窗**展示完整 Key + 复制按钮；关闭后页面列表只显示 `flx_XXXXXXXX...` 前缀。
4. 刷新页面 → 完整 Key 不可恢复（任何接口都不会再返回）。
5. 进入「我的额度」→ 看到余额 `0` 与空流水列表。
6. 切换到 `TENANT_ADMIN`（或自营管理员）→ 进入「额度管理」→ 为该用户增加 `100` 额度，原因「e2e 测试初始化」→ 流水出现 1 条；尝试扣减 `200` → 看到中文「当前可用额度不足，无法完成扣减」，无任何技术细节。
7. 切回 `PLATFORM_ADMIN` → 通过 `/api/admin/api-keys` 与 `/api/admin/credit/transactions` 跨租户查询任意租户数据均可访问。

## 卡密充值

### 批次与单张卡密

卡密归属链：`租户 → 卡密批次 → 单张卡密 → 被本租户用户核销 → 写入额度流水`。

- **卡密批次**（`recharge_card_batch`）：一次提交可含多个面额组，每组独立生成一个批次。批次含 `batchCode`、面额、数量、状态、过期时间。
- **单张卡密**（`recharge_card`）：每张卡密绑定一个批次，含 `cardPrefix`（公开标识，`FLX-XXXX` 格式）与 `cardHash`（HMAC-SHA256 安全哈希）。
- 批次统计（可用 / 已核销 / 已停用 / 已过期数量）通过聚合 SQL 实时计算，不维护冗余列。

### 卡密格式与安全存储

1. **格式**：`FLX-XXXX-XXXX-XXXX-XXXX-XXXX`（5 段 × 4 字符，共 28 字符），Crockford Base32 字符集（排除 `0/O/1/I/l/U`），约 98 bit 熵。
2. **DB 中不保存明文**：仅存 `card_prefix` 与 `card_hash = HMAC-SHA256(normalized_plaintext, card_pepper)` 的 hex。
3. **Pepper 来源**：`fluxora.security.card.pepper`，由环境变量 `CARD_PEPPER` 覆盖；与 API Key pepper 独立，纵深防御。Pepper 绝不进入日志、堆栈、响应或前端。
4. **一次性返回**：完整明文仅在 `POST /api/tenant/{id}/cards/batches` 的响应中返回一次。后续列表、详情、刷新、重新登录均不再返回。前端 `RechargeCardRevealPanel` 强制 non-closable 弹窗展示，关闭后立刻清空内存 ref；不写入 localStorage / sessionStorage / URL。
5. **输入规范化**：核销时用户输入允许大小写、空格、连字符容错；后端统一规范化（大写 → 去空格/连字符 → 重新分段）后再 HMAC 比对。
6. **全局唯一性**：`recharge_card.card_hash` 全局 UNIQUE 索引，保证跨批次、跨租户、跨时间永不碰撞。

### 核销原子事务

同一数据库事务内完成：

1. 规范化输入 → 计算 HMAC → 按 hash 查找卡密
2. 跨租户校验（卡密 `tenant_id` == 当前用户 `tenant_id`）
3. 租户状态校验（未停用、未过期、未删除）
4. 卡密状态预检（未核销、未停用、未过期、批次未停用）
5. 原子 UPDATE：`WHERE status='ENABLED' AND (expire_at IS NULL OR expire_at > NOW())` 且批次 `status='ENABLED'`，`RETURNING denomination` → 0 行表示已被抢先或其他故障
6. 余额 += 面额：复用 `creditMapper.adjustBalance`（`UPDATE…WHERE balance + delta >= 0 RETURNING`）
7. 写流水 `source='CARD_REDEEM'`，绑定 `card_id`
8. 任一失败整事务回滚

**并发安全双层保护**：
- 原子 UPDATE 隐式行锁：N 个并发请求最多 1 个成功
- `uk_credit_txn_card`：`credit_transaction (card_id) WHERE source='CARD_REDEEM'` 部分唯一索引——DB 层保证一张卡密最多一条 CARD_REDEEM 流水。即使应用层逻辑有 bug 也不会重复入账。

### 流水扩展

`credit_transaction` 新增 `source` 列（`MANUAL_ADJUSTMENT` / `CARD_REDEEM`）与 `card_id` 外键。卡密充值流水在用户端展示为「充值类型：卡密充值」，含卡密前缀与批次编号，**不展示完整卡密明文**。

### 角色权限

| 角色 | 核销自己 | 本租户卡密管理 | 跨租户卡密管理 | 本租户充值流水 |
|---|---|---|---|---|
| `PLATFORM_ADMIN` | — | — | ✅ | ✅（跨租户） |
| `TENANT_ADMIN` | ✅ | ✅ | — | ✅ |
| `TENANT_MEMBER` | ✅ | — | — | ✅（仅自己） |

### 浏览器手工验收

1. `admin / Admin@2026!` 登录 → 进入控制台 → 进入「租户管理」→ 任意租户「管理成员」→ 新建一名 `TENANT_MEMBER`（如 `cardtest / Pwd2026Strong`）。
2. 退出，用某租户管理员（如 `e2eadmin / e2epass1234`）登录 → 进入「卡密管理」。
3. 点「新建批次」→ 添加面额组（如 10 额度 × 3 张 + 50 额度 × 2 张）→ 提交。
4. **一次性弹窗**展示 5 张完整卡密 → 点「导出 TXT」或「导出 CSV」下载文件 → 点「我已妥善保存全部卡密」关闭。
5. 关闭后列表只见 `FLX-XXXX...` 前缀；批次详情可见全部卡密元数据。
6. 退出，用 `cardtest` 登录 → 进入「卡密充值」→ 粘贴一张完整卡密（带空格、大小写也行）→ 核销。
7. 看到「充值成功 +N 额度」→ 可用余额增加 → 下方记录显示「卡密充值」。
8. 再次粘贴同一张卡密 → 看到「该卡密已被核销，无法重复使用」中文提示。
9. 切回租户管理员 → 停用某未核销卡密 → 普通用户尝试核销 → 「该卡密已停用，请联系发卡方」。
10. 平台管理员 → 进入「卡密管理」→ 可见所有租户的批次；可跨租户筛选和管理。

## 成员管理

### 成员、角色、租户归属

- 用户账号 `user_account.scope_type` 区分两类作用域：`PLATFORM` 用户（平台管理员）与 `TENANT` 用户（租户成员）。
- 租户成员必须明确归属于某个具体租户（`tenant_id` 非空）。后端不接受通过前端参数推断租户归属：
  - 平台管理员调用 `/api/tenant/{tenantId}/members*` 时显式指定目标租户；
  - 租户管理员调用 `/api/members*` 时后端从 JWT 强制使用 `currentUser.tenantId`，忽略任何客户端入参，跨租户请求一律返回 403。
- 用户状态分为三态：`ENABLED` / `DISABLED` / `DELETED`。状态由 `enabled` 与 `deleted_at` 派生（详见下文「软删除」），软删除遵循 AGENT.md「软删除字段规范」：`deleted_at TIMESTAMPTZ NULL` 时间戳列，`NULL` 表示未删除，非 `NULL` 表示已删除并记录删除时刻；用户名唯一性使用部分唯一索引（`WHERE deleted_at IS NULL`），软删后用户名可被复用。

### 角色权限差异

| 角色 | 列表/详情 | 创建成员 | 编辑资料 | 调整角色 | 启停 | 删除 | 重置密码 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `PLATFORM_ADMIN`（平台管理员） | ✅ 任意租户 | ✅ 任意租户、可创建 `TENANT_ADMIN` 与 `TENANT_MEMBER` | ✅ | ✅ 可在 `TENANT_ADMIN` ↔ `TENANT_MEMBER` 间切换 | ✅ | ✅ | ✅ |
| `TENANT_ADMIN`（租户管理员） | ✅ 本租户 | ✅ 仅 `TENANT_MEMBER` | ✅ 本租户普通成员 | ✅ 仅可分配 `TENANT_MEMBER` | ✅ 不可操作其他 `TENANT_ADMIN` | ✅ 不可删除其他 `TENANT_ADMIN` | ✅ 不可重置其他 `TENANT_ADMIN` 密码 |
| `TENANT_MEMBER`（普通成员） | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |

**禁止项（无论角色，本轮一律不可）**：
- 任何人不可创建 `PLATFORM_ADMIN` 或把成员升级为 `PLATFORM_ADMIN`；
- 租户管理员不可创建/升级/降级任何 `TENANT_ADMIN`。

### 最后一名有效租户管理员保护

为防止误操作导致租户失控，后端服务层在以下三类操作前预检：

- 停用（`PUT /api/members/{id}/disable`）
- 软删除（`DELETE /api/members/{id}`）
- 降级（`PUT /api/members/{id}/role` 由 `TENANT_ADMIN` → 非 `TENANT_ADMIN`）

若目标当前是 `enabled=TRUE` 的 `TENANT_ADMIN`，且执行后该租户内剩余有效 `TENANT_ADMIN` 数量将归零，请求返回 `400 LAST_TENANT_ADMIN_PROTECTED`，前端展示：

> 该租户至少需要保留一名启用状态的租户管理员，无法继续操作

自营租户同样适用该规则；自营租户首位管理员需要先在另一个租户管理员就位后才可被替换。

### 密码规则

- 后端 BCrypt 加密；密码哈希不入响应、不入日志、不入前端状态。
- 强度校验：至少 8 位，含至少 1 个字母与 1 个数字；不符合返回 `PASSWORD_WEAK`。
- 重置密码后旧密码立即失效；前端表单遵循同样的强度规则，提交失败时保留已填写的非敏感字段。

### 浏览器手工验收

按 `## 启动` 节启动 PostgreSQL、Redis、`fluxora-platform`、`fluxora-web` 后，可在浏览器完整走通以下流程，无需手动造数据或调用接口。

1. 以 `admin / Admin@2026!` 登录平台，按向导完成自营租户初始化（已初始化则跳过）。
2. 进入「租户管理」，点击任意租户行尾的 kebab → **管理成员** 进入 `/console/tenants/{id}/members`。
3. 在成员管理页点击 **新增成员**：
   - 角色选择「租户管理员」创建一名 TENANT_ADMIN；
   - 再次新增，角色选择「成员」创建一名 TENANT_MEMBER。
4. 退出登录，使用刚创建的 TENANT_ADMIN 登录：侧边栏出现「成员管理」，进入 `/console/members`，只能看到本租户成员。
5. 在 TENANT_ADMIN 视角下尝试新增成员，角色下拉**仅有「成员」**——不暴露 `PLATFORM_ADMIN`/`TENANT_ADMIN`。
6. 回到平台管理员视角，对普通成员点击「管理成员」→「重置密码」，输入新密码并提交。退出后用**旧密码**登录该成员应失败（提示「用户名或密码错误」），**新密码**应成功。
7. 在租户内只有 1 名 `TENANT_ADMIN` 的情况下，尝试停用 / 删除 / 降级该管理员，应看到「该租户至少需要保留一名启用状态的租户管理员」的中文提示。
8. 软删除某个普通成员后再用相同用户名创建：由于部分唯一索引仅约束未删除记录，新创建应成功。
9. 切换桌面 / 平板 / 移动端视口（1440×900 / 768×1024 / 390×844），确认成员管理页面布局可用，所有失败 toast / dialog 不出现 HTTP 状态码、业务编码、SQL、堆栈或后端原始 message。

## 验证

### 后端
```powershell
# 运行全部后端测试
mvn -pl fluxora-platform test
```

### 前端
```powershell
cd fluxora-web
npm run test -- --run
npm run build
```

### E2E
```powershell
cd fluxora-web
# 需要先启动 platform 和 web
npx playwright test
```

## 错误提示规范

所有面向用户的错误提示遵循 `AGENT.md`：不展示 HTTP 状态码、业务错误编码、Java 异常、SQL 或堆栈。前后端均实现统一安全错误映射。

## 当前阶段边界

### 已实现
- 用户体系：`user_account` 表，PLATFORM / TENANT 双作用域；`deleted_at` 时间戳软删除
- RBAC 权限：`role`、`permission`、`user_role`、`role_permission` 四张表
- 租户体系：`tenant` 表，自营/标准类型，启用/过期/软删除（`deleted_at`，V4 由 `is_deleted` 改造）
- Spring Security + JWT HttpOnly Cookie 认证
- MyBatis XML 全部 SQL
- Flyway 中文注释迁移（V1–V6）
- 平台管理员自动幂等初始化
- 自营租户两步引导初始化
- 租户 CRUD 管理界面
- **成员管理闭环**：分页/详情/创建/编辑/角色调整/启停/软删/重置密码（含跨租户保护、角色升级保护、最后管理员保护、密码强度校验）
- **成员管理前端**：嵌套 `/console/tenants/:tenantId/members`（平台管理员）与 `/console/members`（租户管理员），双视图共用同一组件
- **API Key 管理**：`api_key` 表，HMAC-SHA256 + pepper 安全哈希，完整 plaintext 仅创建响应一次性返回，四态状态派生，双入口路由，跨租户权限隔离
- **用户额度**：`user_credit_account`（DECIMAL 精确存储） + `credit_transaction`（不可篡改流水），`UPDATE…WHERE balance + delta >= 0 RETURNING` 原子调整，并发安全
- **卡密充值**：`recharge_card_batch` + `recharge_card` 两层模型，独立 pepper + HMAC-SHA256 安全哈希，完整明文仅创建响应一次性返回，原子 UPDATE…RETURNING 核销 + 部分唯一索引双层防重复入账；`credit_transaction.source` 扩展为 `MANUAL_ADJUSTMENT` / `CARD_REDEEM`
- **前端页面**：`/console/api-keys`（我的 API Key）、`/console/credit`（我的额度）、`/console/credit/manage`（额度管理）、`/console/cards/redeem`（卡密充值）、`/console/cards/manage`（卡密管理），含一次性 Key 展示组件 `ApiKeyRevealPanel` 与一次性卡密展示组件 `RechargeCardRevealPanel`（含本地 TXT/CSV 导出）
- **上游配置控制面**（V7）：`provider`、`provider_base_url`、`provider_channel`、`provider_credential` 四张表；AES-256-GCM 可逆加密 + HMAC-SHA-256 去重指纹；凭证批量导入（批内去重、一次 IN 查询、批量 INSERT…RETURNING）；部分唯一索引并发兜底；平台共享/租户私有隔离；三个管理页（上游厂商/接入地址/上游通道）+ 通道凭证管理与批量导入
- **上游配置前端**：`/console/providers`、`/console/provider-base-urls`、`/console/provider-channels`；通道详情抽屉内嵌凭证管理面板与批量导入抽屉；StatusDot 脱敏展示；密码输入不回显；MetricStrip 指标条；所有页面复用五行 Grid + Naive UI 骨架

### 刻意未实现（本轮范围外）
- 模型拉取、上游模型发现、模型广场、平台模型、上游模型映射（Model/ModelRoute/RouteTarget — 下一阶段）
- Token 计费、流式实时扣费、用量统计
- 网关 API Key 鉴权、Redis Pub/Sub/Stream、网关本地缓存
- 真实上游请求、连接测试、协议转换/Adapter、SSE 流式转发
- 支付、充值订单、退款、发票
- 凭证自动轮换、失败切换、请求重试
- 短信 / 邮件 / 自助找回密码
- 每个 API Key 独立余额
- 日志审计

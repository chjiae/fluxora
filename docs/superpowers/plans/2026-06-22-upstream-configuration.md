# Fluxora 上游配置控制面 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在既有 RBAC 与多租户控制面上实现 Provider、ProviderBaseUrl、ProviderChannel、ProviderCredential 的安全真实管理、批量导入与前端页面。

**Architecture:** 后端以 Provider → BaseUrl → Channel → Credential 的单向关系组织领域包，所有 SQL 集中于 MyBatis XML。Credential 通过 AES-256-GCM 密文和 HMAC 指纹分离“可逆调用材料”和“不可逆重复判断”，部分唯一索引负责并发兜底。前端复用当前 Naive UI、主题、控制台壳、错误映射与权限菜单，只增业务路由和页面。

**Tech Stack:** JDK 25、Spring Boot 4.1、Spring Security、PostgreSQL、Flyway、MyBatis XML、Vue 3、Pinia、Naive UI、Vitest、Playwright、Testcontainers。

---

## 文件结构

- Create: `fluxora-platform/src/main/resources/db/migration/V7__upstream_configuration.sql` — 四张上游配置表、约束、索引、权限与中文 DDL 注释。
- Create: `fluxora-platform/src/main/java/io/fluxora/platform/upstream/**` — Provider、BaseUrl、Channel、Credential 的实体、DTO、Mapper、Service、Controller、异常与安全服务。
- Create: `fluxora-platform/src/main/resources/mapper/UpstreamMapper.xml` — 唯一的上游配置 SQL 载体。
- Modify: `fluxora-platform/src/main/resources/application.yml` — 开发默认密钥与环境变量覆盖；新增 `application-prod.yml` 生产安全配置。
- Modify: `fluxora-platform/src/main/java/io/fluxora/platform/security/SecurityConfig.java` — 允许/保护新增 REST 路径时沿用 JWT 边界。
- Create: `fluxora-platform/src/test/java/io/fluxora/platform/upstream/**` — Testcontainers 集成测试。
- Create: `fluxora-web/src/services/upstream.ts` — 类型化上游配置 API。
- Create: `fluxora-web/src/views/ProviderManagementView.vue`、`ProviderBaseUrlManagementView.vue`、`ProviderChannelManagementView.vue` — 管理页面。
- Create: `fluxora-web/src/components/CredentialManagementPanel.vue`、`CredentialImportDrawer.vue` — 通道详情凭证管理与导入。
- Modify: `fluxora-web/src/router/index.ts`、`fluxora-web/src/stores/auth.ts`、`fluxora-web/src/components/ConsoleShell.vue` — 权限菜单与路由守卫。
- Modify: `README.md`、`.env.example` — 配置、模型、启动与手工测试说明。

### Task 1: 迁移、权限与安全配置骨架

**Files:**
- Create: `fluxora-platform/src/main/resources/db/migration/V7__upstream_configuration.sql`
- Modify: `fluxora-platform/src/main/resources/application.yml`
- Create: `fluxora-platform/src/main/resources/application-prod.yml`
- Create: `fluxora-platform/src/main/java/io/fluxora/platform/upstream/security/CredentialSecurityProperties.java`
- Create: `fluxora-platform/src/test/java/io/fluxora/platform/upstream/CredentialSecurityPropertiesTest.java`

- [ ] **Step 1: 写出密钥配置失败测试**

```java
@Test
void productionProfileWithoutCredentialKeysMustFailBinding() {
    contextRunner.withPropertyValues("spring.profiles.active=prod")
        .run(context -> assertThat(context).hasFailed());
}

@Test
void localDefaultsAndEnvironmentOverridesBindWithoutExposingValues() {
    contextRunner.run(context -> assertThat(context).hasSingleBean(CredentialSecurityProperties.class));
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl fluxora-platform -Dtest=CredentialSecurityPropertiesTest test`

Expected: FAIL，配置类和生产 profile 尚不存在。

- [ ] **Step 3: 编写 V7 迁移与配置类**

创建 `provider`、`provider_base_url`、`provider_channel`、`provider_credential`。每个表、关键字段、约束和索引都有中文 SQL 注释与 `COMMENT ON TABLE/COLUMN`。添加：Provider 编码的未删除唯一索引、BaseUrl `(provider_id, protocol, normalized_base_url)` 未删除唯一索引、Channel 租户/状态索引、Credential `(tenant_id, credential_fingerprint) WHERE deleted_at IS NULL` 部分唯一索引。迁移以幂等权限 INSERT 建立 `UPSTREAM_READ/CREATE/UPDATE/ENABLE/DISABLE/DELETE/CROSS_TENANT_MANAGE` 并赋予 PLATFORM_ADMIN、TENANT_ADMIN。

以 `@ConfigurationProperties("fluxora.security.credential")` + `@Validated` 绑定 32 字节 Base64 主密钥和指纹密钥；开发 profile 提供明确标注的默认测试值，环境变量覆盖；生产 profile 使用 `@NotBlank` 配置且不含默认值。

- [ ] **Step 4: 运行迁移相关测试**

Run: `mvn -pl fluxora-platform -Dtest=CredentialSecurityPropertiesTest test`

Expected: PASS。

- [ ] **Step 5: 提交基础迁移**

```bash
git add fluxora-platform/src/main/resources/db/migration/V7__upstream_configuration.sql fluxora-platform/src/main/resources/application.yml fluxora-platform/src/main/resources/application-prod.yml fluxora-platform/src/main/java/io/fluxora/platform/upstream/security/CredentialSecurityProperties.java fluxora-platform/src/test/java/io/fluxora/platform/upstream/CredentialSecurityPropertiesTest.java
git commit -m "feat: 添加上游配置数据模型与安全配置"
```

### Task 2: URL 规范化与凭证加密基础服务

**Files:**
- Create: `fluxora-platform/src/main/java/io/fluxora/platform/upstream/security/CredentialCryptoService.java`
- Create: `fluxora-platform/src/main/java/io/fluxora/platform/upstream/ProviderBaseUrlNormalizer.java`
- Create: `fluxora-platform/src/main/java/io/fluxora/platform/upstream/UpstreamException.java`
- Create: `fluxora-platform/src/test/java/io/fluxora/platform/upstream/CredentialCryptoServiceTest.java`
- Create: `fluxora-platform/src/test/java/io/fluxora/platform/upstream/ProviderBaseUrlNormalizerTest.java`

- [ ] **Step 1: 写出 URL 与加密的失败测试**

```java
assertThat(normalizer.normalize("https://api.example.com/v1///")).isEqualTo("https://api.example.com/v1");
assertThatThrownBy(() -> normalizer.normalize("https://api.example.com/v1/chat/completions"))
    .hasMessageContaining("接入基础地址");
assertThat(crypto.decrypt(crypto.encrypt("sk-AbC9"))).isEqualTo("sk-AbC9");
assertThat(crypto.fingerprint(" sk-AbC9\n")).isEqualTo(crypto.fingerprint("sk-AbC9"));
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl fluxora-platform -Dtest=CredentialCryptoServiceTest,ProviderBaseUrlNormalizerTest test`

Expected: FAIL，服务不存在。

- [ ] **Step 3: 实现安全服务**

使用 `AES/GCM/NoPadding`、12 字节随机 IV、128 位认证标签与配置的 32 字节 key；返回密文、IV 和固定版本。HMAC-SHA-256 指纹只对 trim 后非空明文计算，保留大小写。URL 使用 `URI` 校验 scheme/host/query/fragment，规范化 path 尾部 `/`，并拒绝 `chat/completions`、`messages` 等业务路径。所有异常转换为既有统一错误码和中文安全文案，日志不包含明文、密钥、密文或指纹。

- [ ] **Step 4: 验证通过并提交**

Run: `mvn -pl fluxora-platform -Dtest=CredentialCryptoServiceTest,ProviderBaseUrlNormalizerTest test`

```bash
git add fluxora-platform/src/main/java/io/fluxora/platform/upstream fluxora-platform/src/test/java/io/fluxora/platform/upstream
git commit -m "feat: 添加凭证加密与接入地址规范化"
```

### Task 3: Provider 与 BaseUrl 控制面

**Files:**
- Create: `fluxora-platform/src/main/java/io/fluxora/platform/upstream/provider/**`
- Modify: `fluxora-platform/src/main/java/io/fluxora/platform/upstream/UpstreamException.java`
- Modify: `fluxora-platform/src/main/resources/mapper/UpstreamMapper.xml`
- Create: `fluxora-platform/src/test/java/io/fluxora/platform/upstream/ProviderBaseUrlIntegrationTest.java`

- [ ] **Step 1: 写出失败的集成测试**

```java
@Test void sameUrlWithOpenAiAndAnthropicMustBeAllowed() { /* 两协议创建均返回成功 */ }
@Test void sameProviderProtocolAndNormalizedUrlMustBeRejected() { /* 返回安全重复错误 */ }
@Test void tenantAdminCanReadButCannotUpdatePlatformSharedProvider() { /* 403 + 安全文案 */ }
@Test void tenantAdminCannotReadAnotherTenantPrivateProvider() { /* 403/404 不泄露资源 */ }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl fluxora-platform -Dtest=ProviderBaseUrlIntegrationTest test`

Expected: FAIL，接口与 Mapper 不存在。

- [ ] **Step 3: 实现 Provider / BaseUrl 全链路**

实现 typed entity、请求/列表/详情 DTO、Mapper interface 与 XML、Service、Controller。Service 用当前 JWT 身份强制作用域：共享资源仅平台管理员可写；私有资源仅归属租户与平台管理员可见。删除前以一次聚合/exists 查询保护有关联 BaseUrl/Channel 的资源，不物理删除。所有分页/筛选 SQL 写 XML，逻辑删除统一写 `deleted_at`。

- [ ] **Step 4: 运行测试并提交**

Run: `mvn -pl fluxora-platform -Dtest=ProviderBaseUrlIntegrationTest test`

```bash
git add fluxora-platform/src/main/java/io/fluxora/platform/upstream/provider fluxora-platform/src/main/resources/mapper/UpstreamMapper.xml fluxora-platform/src/test/java/io/fluxora/platform/upstream/ProviderBaseUrlIntegrationTest.java
git commit -m "feat: 添加上游厂商与接入地址控制面"
```

### Task 4: Channel 控制面与跨租户隔离

**Files:**
- Create: `fluxora-platform/src/main/java/io/fluxora/platform/upstream/channel/**`
- Modify: `fluxora-platform/src/main/resources/mapper/UpstreamMapper.xml`
- Create: `fluxora-platform/src/test/java/io/fluxora/platform/upstream/ProviderChannelIntegrationTest.java`

- [ ] **Step 1: 写出失败测试**

```java
@Test void tenantCanCreateChannelUsingSharedBaseUrl() { /* 成功 */ }
@Test void tenantCannotReferenceOtherTenantBaseUrl() { /* 安全拒绝 */ }
@Test void disabledProviderOrBaseUrlCannotCreateChannel() { /* 字段级中文错误 */ }
@Test void tenantCannotManageAnotherTenantsChannel() { /* 后端拒绝 */ }
```

- [ ] **Step 2: 运行失败测试**

Run: `mvn -pl fluxora-platform -Dtest=ProviderChannelIntegrationTest test`

Expected: FAIL，Channel 不存在。

- [ ] **Step 3: 实现 Channel**

实现租户归属校验、BaseUrl 可用性校验、priority/weight/timeout 范围校验及分页筛选。平台管理员可显式目标租户并跨租户分页；租户管理员忽略客户端 tenantId 并强制当前 tenant。查询在一条关联 SQL 中取 Provider、协议和 BaseUrl，避免每行加载关联数据。

- [ ] **Step 4: 验证并提交**

Run: `mvn -pl fluxora-platform -Dtest=ProviderChannelIntegrationTest test`

```bash
git add fluxora-platform/src/main/java/io/fluxora/platform/upstream/channel fluxora-platform/src/main/resources/mapper/UpstreamMapper.xml fluxora-platform/src/test/java/io/fluxora/platform/upstream/ProviderChannelIntegrationTest.java
git commit -m "feat: 添加租户上游通道控制面"
```

### Task 5: Credential CRUD、批量导入与并发安全

**Files:**
- Create: `fluxora-platform/src/main/java/io/fluxora/platform/upstream/credential/**`
- Modify: `fluxora-platform/src/main/resources/mapper/UpstreamMapper.xml`
- Create: `fluxora-platform/src/test/java/io/fluxora/platform/upstream/ProviderCredentialIntegrationTest.java`

- [ ] **Step 1: 写出失败测试**

```java
@Test void responseNeverContainsPlaintextCiphertextIvOrFingerprint() { /* JSON 字段不存在 */ }
@Test void disabledExistingCredentialIsSkippedOnImport() { /* imported=0, skippedExisting=1 */ }
@Test void softDeletedCredentialCanBeImportedAgain() { /* imported=1 */ }
@Test void duplicateLinesOnlyInsertFirst() { /* 一次批量写入一条 */ }
@Test void concurrentImportAllowsAtMostOneActiveFingerprint() { /* 两并发请求最多一条写入 */ }
```

- [ ] **Step 2: 运行失败测试**

Run: `mvn -pl fluxora-platform -Dtest=ProviderCredentialIntegrationTest test`

Expected: FAIL，Credential 端点不存在。

- [ ] **Step 3: 实现 Credential 与导入**

创建、元数据编辑、替换、启停、软删除、分页和详情全部检查 Channel/tenant 归属。导入请求携带 `List<String>` 或服务端解析后的文本行；按行生成脱敏展示与指纹，批内 `Set` 判重，一次 `IN` 查询租户现存指纹，一次 `<foreach>` 多行 INSERT 写入。捕获部分唯一索引冲突并把对应行标记为并发跳过；不循环调用 Mapper。输入与结果 DTO 禁止带敏感字段回到前端，替换后旧密文不会再被引用。

- [ ] **Step 4: 验证并提交**

Run: `mvn -pl fluxora-platform -Dtest=ProviderCredentialIntegrationTest test`

```bash
git add fluxora-platform/src/main/java/io/fluxora/platform/upstream/credential fluxora-platform/src/main/resources/mapper/UpstreamMapper.xml fluxora-platform/src/test/java/io/fluxora/platform/upstream/ProviderCredentialIntegrationTest.java
git commit -m "feat: 添加上游凭证加密与批量导入"
```

### Task 6: 前端服务、权限菜单与管理页面

**Files:**
- Create: `fluxora-web/src/services/upstream.ts`
- Modify: `fluxora-web/src/stores/auth.ts`
- Modify: `fluxora-web/src/router/index.ts`
- Modify: `fluxora-web/src/components/ConsoleShell.vue`
- Create: `fluxora-web/src/views/ProviderManagementView.vue`
- Create: `fluxora-web/src/views/ProviderBaseUrlManagementView.vue`
- Create: `fluxora-web/src/views/ProviderChannelManagementView.vue`
- Create: `fluxora-web/src/components/CredentialManagementPanel.vue`
- Create: `fluxora-web/src/components/CredentialImportDrawer.vue`
- Create: `fluxora-web/src/__tests__/upstream-management.spec.ts`

- [ ] **Step 1: 写出失败的前端测试**

```ts
it('普通成员不显示上游配置菜单且访问路由安全跳转', async () => { /* 权限 mock */ })
it('共享 Provider 在租户管理员视图中只读', async () => { /* 编辑/删除不可用且有原因 */ })
it('导入结果只显示脱敏标识并在关闭后清空输入', async () => { /* 不包含 sk-完整值 */ })
```

- [ ] **Step 2: 运行失败测试**

Run: `npm run test -- --run src/__tests__/upstream-management.spec.ts --pool=forks --maxWorkers=1`

Expected: FAIL，服务、菜单和页面不存在。

- [ ] **Step 3: 实现页面与真实接口联通**

`upstream.ts` 只使用 typed DTO/axios。菜单根据新增权限显示“上游配置 / 上游厂商 / 接入地址 / 上游通道”；路由守卫与后端权限一致。Provider/BaseUrl/Channel 复用现有 PageHeader、AsyncState、StatusTag、ConsoleShell、Naive UI 表格/抽屉/Form。Credential 面板使用 password input；文件导入仅在浏览器读取 TXT/CSV 文本，限制类型/数量，提交完成、取消、关闭结果后都清空 plaintext ref 与 file 内容。所有提示只用安全中文映射，绝不渲染 raw error。

- [ ] **Step 4: 验证并提交**

Run: `npm run test -- --run --pool=forks --maxWorkers=1 && npm run build`

```bash
git add fluxora-web/src/services/upstream.ts fluxora-web/src/stores/auth.ts fluxora-web/src/router/index.ts fluxora-web/src/components/ConsoleShell.vue fluxora-web/src/views/ProviderManagementView.vue fluxora-web/src/views/ProviderBaseUrlManagementView.vue fluxora-web/src/views/ProviderChannelManagementView.vue fluxora-web/src/components/CredentialManagementPanel.vue fluxora-web/src/components/CredentialImportDrawer.vue fluxora-web/src/__tests__/upstream-management.spec.ts
git commit -m "feat: 添加上游配置管理页面"
```

### Task 7: 端到端验收、README 与最终安全扫描

**Files:**
- Create: `fluxora-web/e2e/upstream-configuration.spec.ts`
- Modify: `README.md`
- Modify: `.env.example`

- [ ] **Step 1: 写真实 Playwright 流程**

```ts
test('共享地址、私有通道与脱敏批量导入流程', async ({ page }) => {
  // 管理员创建共享 Provider 与同 URL 的 OPENAI/ANTHROPIC BaseUrl
  // 租户管理员创建私有 Provider、选择共享 BaseUrl 创建 Channel
  // 导入重复凭证，断言汇总与明细仅出现脱敏值
})
```

- [ ] **Step 2: 运行并确认缺失流程失败**

Run: `npx playwright test e2e/upstream-configuration.spec.ts`

Expected: FAIL，直至前端和后端完整联通。

- [ ] **Step 3: 完成 README 与安全扫描**

README 说明四层关系、共享/私有边界、URL 规则、开发默认值与生产环境变量、脱敏/批量导入/软删除规则、启动与手工流程、已实现和未实现边界。`.env.example` 只列变量名与安全说明，不写真实密钥。扫描不得出现请求输入值进入日志、响应 DTO 或浏览器持久化。

```bash
rg -n "credentialPlaintext|masterKey|fingerprintKey|ciphertext|initializationVector" fluxora-platform/src/main/java fluxora-web/src
```

Expected: 仅限内部加密服务、持久化实体/Mapper 和安全测试；前端没有完整凭证持久化或显示。

- [ ] **Step 4: 执行完整验证并提交**

Run: `mvn -pl fluxora-platform test && npm --prefix fluxora-web run test -- --run --pool=forks --maxWorkers=1 && npm --prefix fluxora-web run build && npx --prefix fluxora-web playwright test`

```bash
git add README.md .env.example fluxora-web/e2e/upstream-configuration.spec.ts
git commit -m "docs: 补充上游配置说明与验收"
git status --short
```

## 自检

- 设计中的四层关系、安全配置、URL 规范化、共享/私有隔离、导入去重、前端复用和 README 分别由 Task 1–7 覆盖。
- 批量导入明确使用批内集合、一次 IN 查询和多行 INSERT，避免循环接口、Mapper 调用或 N+1。
- 所有 SQL 明确限制在 `UpstreamMapper.xml`；所有公开 DTO 与前端结果排除敏感加密字段与明文。
- 本计划不引入模型、路由、Redis、网关或真实上游调用。

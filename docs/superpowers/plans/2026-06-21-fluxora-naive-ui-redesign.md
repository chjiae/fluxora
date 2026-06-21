# Fluxora Naive UI 全面视觉重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变现有真实认证、权限和租户 CRUD 链路的前提下，以 Naive UI 统一重构 Fluxora Web 的公开区、登录、初始化和控制台界面，并移除被替代的手写 UI。

**Architecture:** 使用全局 Naive UI `themeOverrides` 与一个 Pinia 主题 store 管理亮暗外观。将公共导航、主题切换、控制台壳、页面标题和异步状态收敛为小型可复用组件；页面只组合这些组件、Naive UI 和既有 service/store。全局 CSS 只保留 token、reset 与可访问性规则，页面布局样式留在各页面作用域内。

**Tech Stack:** Vue 3、TypeScript、Vue Router、Pinia、Naive UI 2.x、Lucide Vue、Vite、Vitest、Playwright。

---

## 文件结构

- Create: `fluxora-web/src/components/ThemeToggle.vue` — 全站主题切换与可访问标签。
- Create: `fluxora-web/src/components/PublicHeader.vue` — 公开首页和文档的导航、移动菜单、主题入口。
- Create: `fluxora-web/src/components/ConsoleShell.vue` — 固定桌面侧栏、移动抽屉、顶部栏和权限菜单。
- Create: `fluxora-web/src/components/PageHeader.vue` — 控制台页面标题、说明、主操作和工具栏插槽。
- Create: `fluxora-web/src/components/StatusTag.vue` — 租户类型、状态、受保护状态的语义标签。
- Create: `fluxora-web/src/components/AsyncState.vue` — loading / empty / error 三种可复用状态。
- Modify: `fluxora-web/src/styles.css` — 删除旧手写组件样式，保留设计 token 和基础规则。
- Modify: `fluxora-web/src/stores/theme.ts` — 严格类型化 Naive UI 覆盖项和系统主题监听。
- Modify: `fluxora-web/src/App.vue` — 提供中文 Naive UI locale、主题和全局反馈 provider。
- Modify: `fluxora-web/src/views/HomeView.vue` — 重构公开首页。
- Modify: `fluxora-web/src/views/DocsView.vue` — 重构文档阅读布局。
- Modify: `fluxora-web/src/views/LoginView.vue` — 重构登录体验与字段校验。
- Modify: `fluxora-web/src/views/SelfOperatedSetupView.vue` — 重构两步初始化流程。
- Modify: `fluxora-web/src/views/ConsoleView.vue` — 改为使用 `ConsoleShell`，固定外壳与内容滚动边界。
- Modify: `fluxora-web/src/views/TenantManagementView.vue` — 重构筛选、表格、抽屉、表单和操作分组。
- Modify: `fluxora-web/src/router/index.ts` — 使用无内容重复的子路由/空组件替代方案，让概览仅在控制台壳内渲染一次。
- Modify: `fluxora-web/src/__tests__/router.spec.ts`、`fluxora-web/src/__tests__/auth-router.spec.ts`、`fluxora-web/src/__tests__/tenant-management.spec.ts` — 适配组件边界并覆盖关键 UI 行为。
- Modify: `fluxora-web/e2e/identity-tenancy.spec.ts`（如该文件存在）— 补全主题、断点、固定侧栏和可发现操作的验收。

### Task 1: 先用测试锁定主题、导航与通用组件契约

**Files:**
- Create: `fluxora-web/src/components/__tests__/ThemeToggle.spec.ts`
- Create: `fluxora-web/src/components/__tests__/StatusTag.spec.ts`
- Create: `fluxora-web/src/components/__tests__/AsyncState.spec.ts`
- Modify: `fluxora-web/src/stores/theme.ts`
- Create: `fluxora-web/src/components/ThemeToggle.vue`
- Create: `fluxora-web/src/components/StatusTag.vue`
- Create: `fluxora-web/src/components/AsyncState.vue`

- [ ] **Step 1: 写出失败的主题与状态组件测试**

```ts
it('点击主题切换会调用主题 store，并给出下一主题的可访问标签', async () => {
  const wrapper = mount(ThemeToggle, { global: { plugins: [pinia] } })
  await wrapper.get('button[aria-label="切换为亮色主题"]').trigger('click')
  expect(themeStore.toggle).toHaveBeenCalledOnce()
})

it.each([
  ['SELF_OPERATED', 'type', '自营'],
  ['ENABLED', 'status', '启用'],
  ['EXPIRED', 'status', '已过期'],
])('渲染 %s 为中文语义标签', (value, kind, label) => {
  expect(mount(StatusTag, { props: { value, kind } }).text()).toContain(label)
})

it('错误状态只展示安全文案与重试入口', () => {
  const wrapper = mount(AsyncState, { props: { state: 'error', description: '服务暂时不可用，请稍后重试' } })
  expect(wrapper.text()).toContain('服务暂时不可用，请稍后重试')
  expect(wrapper.text()).not.toMatch(/500|SQLException|stack/i)
})
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `npm run test -- --run src/components/__tests__/ThemeToggle.spec.ts src/components/__tests__/StatusTag.spec.ts src/components/__tests__/AsyncState.spec.ts --pool=forks --maxWorkers=1`

Expected: FAIL，提示三个组件尚不存在。

- [ ] **Step 3: 实现最小通用组件与主题 token**

`ThemeToggle.vue` 使用 Naive UI `NButton`、`NTooltip` 和 Lucide 图标；按钮必须有文字可读的 `aria-label`。`StatusTag.vue` 将 `SELF_OPERATED` 映射为“自营”，`STANDARD` 映射为“标准”，状态映射为“启用/停用/已过期/已删除”；`AsyncState.vue` 使用 `NSkeleton`、`NEmpty`、`NResult` 和明确重试事件。

`theme.ts` 中的 `buildOverrides` 只返回 Naive UI 支持的 `GlobalThemeOverrides` 字段：冷白 `#f7f8fa` / 墨黑 `#11151c` 表面，品牌蓝 `#4f7cff`，语义色与中性色；不再保留青绿色或暖灰 token。系统偏好监听只在浏览器环境注册，并在 store 释放时取消。

- [ ] **Step 4: 重新运行组件测试**

Run: `npm run test -- --run src/components/__tests__/ThemeToggle.spec.ts src/components/__tests__/StatusTag.spec.ts src/components/__tests__/AsyncState.spec.ts --pool=forks --maxWorkers=1`

Expected: PASS。

- [ ] **Step 5: 提交通用视觉基础**

```bash
git add fluxora-web/src/stores/theme.ts fluxora-web/src/components/ThemeToggle.vue fluxora-web/src/components/StatusTag.vue fluxora-web/src/components/AsyncState.vue fluxora-web/src/components/__tests__
git commit -m "feat: 建立前端主题与状态组件"
```

### Task 2: 统一 Naive UI 配置并清理旧全局 CSS

**Files:**
- Modify: `fluxora-web/src/App.vue`
- Modify: `fluxora-web/src/styles.css`
- Modify: `fluxora-web/src/main.ts`
- Test: `fluxora-web/src/__tests__/router.spec.ts`

- [ ] **Step 1: 写出全局基础样式回归测试**

```ts
it('应用根节点始终提供 Naive UI 的主题和反馈 provider', () => {
  const wrapper = mount(App, { global: { plugins: [router, pinia] } })
  expect(wrapper.findComponent({ name: 'NConfigProvider' }).exists()).toBe(true)
  expect(wrapper.findComponent({ name: 'NMessageProvider' }).exists()).toBe(true)
})
```

- [ ] **Step 2: 运行该测试并确认当前实现不满足新增 locale 断言**

Run: `npm run test -- --run src/__tests__/router.spec.ts --pool=forks --maxWorkers=1`

Expected: FAIL，直到测试加入 `zhCN`/`dateZhCN` 断言并应用实现。

- [ ] **Step 3: 实现全局样式收敛**

在 `App.vue` 从 `naive-ui` 引入 `zhCN` 与 `dateZhCN`，传给 `NConfigProvider`。重写 `styles.css`，仅保留以下内容：font stacks、亮暗 CSS 变量、`box-sizing`/body reset、链接/代码样式、`:focus-visible`、`prefers-reduced-motion` 和页面最低高度。删除 `.button`、`.field`、`.login-*`、`.setup-*`、旧 `.console > aside`、`.console-main`、`.metrics`、旧 `.docs`、`.theme-toggle` 以及所有内联控件替代样式；不保留无效 `aria-label` CSS 声明。

- [ ] **Step 4: 运行测试与生产构建**

Run: `npm run test -- --run src/__tests__/router.spec.ts --pool=forks --maxWorkers=1 && npm run build`

Expected: PASS；`vue-tsc -b && vite build` 成功完成。

- [ ] **Step 5: 提交全局 Naive UI 基础**

```bash
git add fluxora-web/src/App.vue fluxora-web/src/main.ts fluxora-web/src/styles.css fluxora-web/src/__tests__/router.spec.ts
git commit -m "refactor: 统一Naive UI全局样式体系"
```

### Task 3: 重构公开首页与文档阅读体验

**Files:**
- Create: `fluxora-web/src/components/PublicHeader.vue`
- Modify: `fluxora-web/src/views/HomeView.vue`
- Modify: `fluxora-web/src/views/DocsView.vue`
- Create: `fluxora-web/src/components/__tests__/PublicHeader.spec.ts`

- [ ] **Step 1: 写出失败的导航响应式测试**

```ts
it('移动菜单打开后仍能访问文档、控制台与主题切换', async () => {
  const wrapper = mount(PublicHeader, { global: { plugins: [router, pinia] } })
  await wrapper.get('[aria-label="打开导航菜单"]').trigger('click')
  expect(wrapper.text()).toContain('文档')
  expect(wrapper.text()).toContain('进入控制台')
  expect(wrapper.findComponent(ThemeToggle).exists()).toBe(true)
})
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `npm run test -- --run src/components/__tests__/PublicHeader.spec.ts --pool=forks --maxWorkers=1`

Expected: FAIL，`PublicHeader.vue` 尚不存在。

- [ ] **Step 3: 实现公开区组件与页面**

`PublicHeader` 桌面端使用 `NLayoutHeader`/`NSpace`，移动端使用 `NDrawer` 放置完整导航，不把主题按钮藏进仅图标的未知动作里。首页删除手写 `.header/.hero/.button` 依赖，改为 Naive UI 按钮、卡片只在信息确实需分组时使用；请求流转示意使用 HTML/CSS 网格，不引入图片或渐变。文档正文用 `NLayout`、`NLayoutSider`、`NDrawer`（移动目录）、`NAnchor`/可点击目录和 `NCode` 风格代码块，保持 mock 内容与现有路由不变。

- [ ] **Step 4: 运行单元测试与构建**

Run: `npm run test -- --run src/components/__tests__/PublicHeader.spec.ts src/__tests__/router.spec.ts --pool=forks --maxWorkers=1 && npm run build`

Expected: PASS。

- [ ] **Step 5: 提交公开页面重构**

```bash
git add fluxora-web/src/components/PublicHeader.vue fluxora-web/src/components/__tests__/PublicHeader.spec.ts fluxora-web/src/views/HomeView.vue fluxora-web/src/views/DocsView.vue
git commit -m "feat: 重构公开首页与文档体验"
```

### Task 4: 重构登录与自营租户初始化流程

**Files:**
- Modify: `fluxora-web/src/views/LoginView.vue`
- Modify: `fluxora-web/src/views/SelfOperatedSetupView.vue`
- Modify: `fluxora-web/src/__tests__/auth-router.spec.ts`

- [ ] **Step 1: 写出失败的字段级校验与密码确认测试**

```ts
it('登录缺少字段时在表单附近显示安全中文提示且不请求接口', async () => {
  const wrapper = mount(LoginView, { global: { plugins: [router, pinia, naive] } })
  await wrapper.get('form').trigger('submit')
  expect(auth.loginAction).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('请输入用户名')
})

it('初始化密码与确认密码不一致时不提交', async () => {
  const wrapper = mount(SelfOperatedSetupView, { global: { plugins: [router, pinia, naive] } })
  await wrapper.findAll('input').at(2)!.setValue('Admin@2026!')
  await wrapper.findAll('input').at(3)!.setValue('different')
  await wrapper.get('form').trigger('submit')
  expect(auth.initSelfOperated).not.toHaveBeenCalled()
})
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `npm run test -- --run src/__tests__/auth-router.spec.ts --pool=forks --maxWorkers=1`

Expected: FAIL，当前初始化没有确认密码字段，登录没有表单规则。

- [ ] **Step 3: 使用 Naive UI 表单规则完成最小实现**

使用 `NForm`、`FormInst` 和 `FormRules`；用户名、密码、显示名称在提交前执行字段级校验。密码输入 suffix 使用具有 `aria-label` 的文字化 tooltip 和 Lucide 眼睛图标。初始化拆分为“租户确认”和“管理员创建”两步，第二步新增 `confirmPassword`，只有校验通过才调用已有 `auth.initSelfOperated`。所有 catch 继续使用 `userMessage` 或现有安全默认文案，绝不将原始异常渲染到页面。

- [ ] **Step 4: 运行认证测试和构建**

Run: `npm run test -- --run src/__tests__/auth-router.spec.ts --pool=forks --maxWorkers=1 && npm run build`

Expected: PASS。

- [ ] **Step 5: 提交认证页面重构**

```bash
git add fluxora-web/src/views/LoginView.vue fluxora-web/src/views/SelfOperatedSetupView.vue fluxora-web/src/__tests__/auth-router.spec.ts
git commit -m "feat: 优化登录与自营初始化交互"
```

### Task 5: 建立固定控制台外壳与权限菜单

**Files:**
- Create: `fluxora-web/src/components/ConsoleShell.vue`
- Create: `fluxora-web/src/components/PageHeader.vue`
- Modify: `fluxora-web/src/views/ConsoleView.vue`
- Modify: `fluxora-web/src/router/index.ts`
- Test: `fluxora-web/src/__tests__/router.spec.ts`

- [ ] **Step 1: 写出失败的控制台壳测试**

```ts
it('平台管理员可见租户管理，租户管理员不渲染该菜单', async () => {
  auth.permissions = ['TENANT_READ']
  expect(mount(ConsoleShell, { global: { plugins: [router, pinia] } }).text()).toContain('租户管理')
  auth.permissions = []
  expect(mount(ConsoleShell, { global: { plugins: [router, pinia] } }).text()).not.toContain('租户管理')
})

it('控制台只有内容区滚动，侧栏与顶部栏保持固定', () => {
  const wrapper = mount(ConsoleShell, { global: { plugins: [router, pinia] } })
  expect(wrapper.find('[data-testid="console-content"]').classes()).toContain('console-content')
})
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `npm run test -- --run src/__tests__/router.spec.ts --pool=forks --maxWorkers=1`

Expected: FAIL，`ConsoleShell` 尚不存在。

- [ ] **Step 3: 实现固定控制台布局**

实现 `ConsoleShell`：顶层 `NLayout has-sider` 高度为 `100dvh`，`NLayoutSider` 与 `NLayoutHeader` 使用固定高度/不滚动，`NLayoutContent` 作为唯一 `overflow:auto` 区域并带 `data-testid="console-content"`。菜单由 `auth.canReadTenants` 生成，使用当前 `route.path` 作为 `NMenu` 值；窄屏以 `NDrawer` 复用相同菜单。顶栏主题切换使用 `ThemeToggle`，用户菜单使用 `NDropdown`，退出是带文字的操作。将概览路由改为一个真实 `OverviewView` 或在壳内单一默认 slot 渲染，消除空模板子路由与双重条件渲染。

- [ ] **Step 4: 运行路由测试和构建**

Run: `npm run test -- --run src/__tests__/router.spec.ts src/__tests__/auth-router.spec.ts --pool=forks --maxWorkers=1 && npm run build`

Expected: PASS。

- [ ] **Step 5: 提交控制台外壳**

```bash
git add fluxora-web/src/components/ConsoleShell.vue fluxora-web/src/components/PageHeader.vue fluxora-web/src/views/ConsoleView.vue fluxora-web/src/router/index.ts fluxora-web/src/__tests__/router.spec.ts
git commit -m "feat: 重构控制台导航与固定布局"
```

### Task 6: 重构租户管理表格、表单与操作分组

**Files:**
- Modify: `fluxora-web/src/views/TenantManagementView.vue`
- Modify: `fluxora-web/src/__tests__/tenant-management.spec.ts`
- Test: `fluxora-web/e2e/identity-tenancy.spec.ts`

- [ ] **Step 1: 写出失败的租户页面交互测试**

```ts
it('自营租户显示受保护原因且不提供停用、删除和过期动作', async () => {
  tenantApi.listTenants.mockResolvedValue({ total: 1, items: [selfOperatedTenant] })
  const wrapper = mount(TenantManagementView, { global: { plugins: [router, pinia, naive] } })
  await flushPromises()
  await wrapper.get('[data-tenant-id="self-tenant"]').trigger('click')
  expect(wrapper.text()).toContain('自营租户受保护')
  expect(wrapper.text()).not.toContain('确认停用')
  expect(wrapper.text()).not.toContain('删除租户')
})

it('新建表单缺少租户码时显示字段反馈而不调用创建接口', async () => {
  const wrapper = mount(TenantManagementView, { global: { plugins: [router, pinia, naive] } })
  await wrapper.get('[data-testid="create-tenant"]').trigger('click')
  await wrapper.get('form').trigger('submit')
  expect(tenantApi.createTenant).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('请输入租户码')
})
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `npm run test -- --run src/__tests__/tenant-management.spec.ts --pool=forks --maxWorkers=1`

Expected: FAIL，当前表单无规则且详情抽屉未采用统一结构。

- [ ] **Step 3: 实现表格与抽屉重构**

保留 `NDataTable` 的类型化 `DataTableColumns<Tenant>` 和 `h()` 渲染；Naive UI 官方示例明确支持列 `render` 返回 VNode，因此不得为规避构建问题而删除状态标签或操作列。显式导入 `h`、`NTag`、`NButton`、`NDropdown` 和所需类型，避免错误的 `declare module 'naive-ui'` shim。

将筛选区置于可折叠 `NCard`/`NCollapse` 内，使用 `NForm` 规则校验新建与编辑表单。详情抽屉使用布尔 `showDetailDrawer` 与 `NDrawerContent`；不可绑定 `Tenant | null` 给 `v-model:show`。表格为名称、租户码、类型、状态、过期时间、更新时间、操作列；主操作“详情”带文字，低频动作收进“更多操作”下拉菜单。危险动作继续使用 `NDialog` 二次确认，自营租户显示禁用原因，所有成功后调用一次 `loadTenants()` 刷新数据。

- [ ] **Step 4: 运行页面测试、构建与真实流程测试**

Run: `npm run test -- --run src/__tests__/tenant-management.spec.ts --pool=forks --maxWorkers=1 && npm run build && npx playwright test e2e/identity-tenancy.spec.ts`

Expected: 单元测试、构建和可运行环境下的 Playwright 流程均 PASS。

- [ ] **Step 5: 提交租户管理重构**

```bash
git add fluxora-web/src/views/TenantManagementView.vue fluxora-web/src/__tests__/tenant-management.spec.ts fluxora-web/e2e/identity-tenancy.spec.ts
git commit -m "feat: 重构租户管理交互体验"
```

### Task 7: 多视口视觉验收与遗留样式清理

**Files:**
- Modify: `fluxora-web/e2e/identity-tenancy.spec.ts`
- Modify: `fluxora-web/src/styles.css`

- [ ] **Step 1: 写出 Playwright 视觉行为断言**

```ts
for (const viewport of [
  { name: 'desktop', width: 1440, height: 960 },
  { name: 'tablet', width: 900, height: 900 },
  { name: 'mobile', width: 390, height: 844 },
]) {
  test(`${viewport.name} 可切换主题并保持可访问导航`, async ({ page }) => {
    await page.setViewportSize(viewport)
    await page.goto('/')
    await page.getByRole('button', { name: /切换为.*主题/ }).click()
    await expect(page.locator('html')).toHaveAttribute('data-theme', /light|dark/)
    await page.getByRole('link', { name: '文档' }).click()
    await expect(page.getByRole('heading', { name: '平台接入说明' })).toBeVisible()
  })
}
```

- [ ] **Step 2: 运行 Playwright 并确认新增断言在旧结构下失败**

Run: `npx playwright test e2e/identity-tenancy.spec.ts --project=desktop`

Expected: FAIL，直到新的可访问标签和响应式导航完成。

- [ ] **Step 3: 修复所有断点问题并做最终样式审计**

在桌面确认只有 `console-content` 滚动；在平板确认筛选不溢出；在移动端确认公开导航/控制台菜单均可打开、抽屉可关闭、表格保留详情入口。用 `rg` 确认旧 CSS 选择器已删除：

```bash
rg -n "(^|\\s)\\.(button|field|console-main|login-hero|setup-card|theme-toggle)\\b|aria-label:" fluxora-web/src/styles.css
```

Expected: 无匹配；如仍有有效组件自身的局部类，应改为与新组件唯一对应的名字后再次检查。

- [ ] **Step 4: 完整验证**

Run: `npm run test -- --run --pool=forks --maxWorkers=1 && npm run build && npx playwright test`

Expected: Vitest、生产构建、所有可运行的 Playwright 测试通过；若环境依赖导致跳过，报告跳过的项目与原因，不将跳过视为通过。

- [ ] **Step 5: 提交验证与最终清理**

```bash
git add fluxora-web/src/styles.css fluxora-web/e2e/identity-tenancy.spec.ts
git commit -m "test: 补充前端多视口视觉验收"
git status --short
```

## 计划自检

- 规格覆盖：主题、Naive UI 单一组件体系、公开区、文档、登录、初始化、固定控制台、权限菜单、租户表格和多视口验证均分别落在任务 1–7。
- 非目标保持：所有任务只修改 `fluxora-web`；不触碰后端、数据库、接口契约或新的业务领域。
- 关键兼容性：Task 6 明确保留 Naive UI 官方支持的 `NDataTable` `h()` 渲染，并使用独立布尔值控制 `NDrawer`。
- 清理目标明确：Task 2 和 Task 7 列明旧 CSS 的删除范围与检索命令，避免两套 UI 样式并存。

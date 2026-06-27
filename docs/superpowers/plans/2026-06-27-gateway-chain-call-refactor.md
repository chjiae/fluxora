# Gateway 链式调用可调试性改造 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `fluxora-gateway` 模块的 stream 流与 Vert.x Future 链改造为可逐步调试的形态（显式循环 + 命名方法 + 中文注释），不改变执行逻辑、类设计与性能。

**Architecture:** A 类（stream 流，3 文件）转为显式 `for` 循环 + 命名中间变量，谓词与比较条件原样保留；B 类（Future 链，4 文件）抽取 `private` 命名方法并补充中文注释，`compose/eventually/onSuccess/onFailure` 顺序与异步语义完全不变。两类改造均不新增/删除类、不改公共方法签名。

**Tech Stack:** Java 21、Vert.x 4（Future / HttpClient / Redis）、Caffeine AsyncCache、JUnit 5。

**Spec:** `docs/superpowers/specs/2026-06-27-gateway-chain-call-refactor-design.md`

---

## 红线（每个任务都必须遵守）

1. **执行逻辑不变** — 谓词、比较条件、判断分支、调度算法的确定性、Future 组合顺序、租约获取/释放时序、成功/失败分支语义均不得改变。
2. **类设计不变** — 不新增/删除类、不改公共方法签名、不改构造参数、不改字段。B 类只新增 `private` 方法。
3. **性能不降** — stream→循环去掉 Stream 流水线开销（持平或略优）；Future 抽方法不引入额外 Future 跳数、不在热路径新增闭包对象。
4. **中文注释规范** — 说明业务意图、约束、边界、状态流转原因或安全考虑，不机械重复代码字面含义。

## 回归验证（贯穿所有任务）

改造涉及的全部是已有单测覆盖的逻辑。每个任务改造后，单独运行该文件相关的测试；全部完成后运行 gateway 模块全量测试。

```bash
# 单文件相关测试示例（Windows Git Bash）
./mvnw -pl fluxora-gateway test -Dtest=UpstreamDispatchPlannerTest
```

最终全量：
```bash
./mvnw -pl fluxora-gateway test
```

所有测试必须全部通过，不得修改任何测试文件。

---

## Task 1: UpstreamDispatchPlanner — stream 流转显式循环

**Files:**
- Modify: `fluxora-gateway/src/main/java/io/fluxora/gateway/relay/scheduling/UpstreamDispatchPlanner.java:38-39,55-58,73-76`
- Test（回归，不改）: `fluxora-gateway/src/test/java/io/fluxora/gateway/relay/scheduling/UpstreamDispatchPlannerTest.java`

本任务有 3 处 stream 改造点，全部位于同一文件。逐处替换，谓词与比较条件原样保留。

- [ ] **Step 1: 改造 `planAndAcquire` 的 tier 选择（第 38-39 行）**

把：
```java
        int tier = candidates.stream().map(DispatchCandidate::priorityTier).min(Comparator.naturalOrder()).orElse(Integer.MAX_VALUE);
        List<DispatchCandidate> tierCandidates = candidates.stream().filter(c -> c.priorityTier() == tier).toList();
```
替换为：
```java
        // 选取最高可用优先级层（数值越小优先级越高），流量优先落在更高优先级的 Tier
        int highestPriorityTier = Integer.MAX_VALUE;
        for (DispatchCandidate candidate : candidates) {
            highestPriorityTier = Math.min(highestPriorityTier, candidate.priorityTier());
        }
        // 只保留当前最高优先级 Tier 的候选目标，低优先级 Tier 仅在当前层无可用候选时才会在下一轮被选中
        List<DispatchCandidate> tierCandidates = new ArrayList<>();
        for (DispatchCandidate candidate : candidates) {
            if (candidate.priorityTier() == highestPriorityTier) {
                tierCandidates.add(candidate);
            }
        }
```

- [ ] **Step 2: 改造 `selectChannel` 的 minActive 计算 + leastActive 过滤（第 55-58 行）**

把：
```java
        long minActive = channelIds.stream()
                .mapToLong(id -> leaseManager.activeCount("channel", Long.toString(id))).min().orElse(0L);
        List<Long> leastActive = channelIds.stream()
                .filter(id -> leaseManager.activeCount("channel", Long.toString(id)) == minActive).toList();
```
替换为：
```java
        // 计算各 Channel 当前活跃连接数的最小值，用于筛选"最空闲"的 Channel
        long minActive = 0L;
        boolean first = true;
        for (Long channelId : channelIds) {
            long active = leaseManager.activeCount("channel", Long.toString(channelId));
            if (first || active < minActive) {
                minActive = active;
                first = false;
            }
        }
        // 收集所有活跃数等于最小值的 Channel，使流量在并列最空闲的 Channel 间轮询
        List<Long> leastActive = new ArrayList<>();
        for (Long channelId : channelIds) {
            if (leaseManager.activeCount("channel", Long.toString(channelId)) == minActive) {
                leastActive.add(channelId);
            }
        }
```

- [ ] **Step 3: 改造 `selectCredential` 的 minActive 计算 + leastActive 过滤（第 73-76 行）**

把：
```java
        long minActive = credentials.stream()
                .mapToLong(c -> leaseManager.activeCount("credential", Long.toString(c.providerCredentialId()))).min().orElse(0L);
        List<DispatchCandidate> leastActive = credentials.stream()
                .filter(c -> leaseManager.activeCount("credential", Long.toString(c.providerCredentialId())) == minActive).toList();
```
替换为：
```java
        // 计算各凭证当前活跃连接数的最小值，用于筛选"最空闲"的凭证
        long minActive = 0L;
        boolean first = true;
        for (DispatchCandidate credential : credentials) {
            long active = leaseManager.activeCount("credential", Long.toString(credential.providerCredentialId()));
            if (first || active < minActive) {
                minActive = active;
                first = false;
            }
        }
        // 收集所有活跃数等于最小值的凭证，使流量在并列最空闲的凭证间轮询
        List<DispatchCandidate> leastActive = new ArrayList<>();
        for (DispatchCandidate credential : credentials) {
            if (leaseManager.activeCount("credential", Long.toString(credential.providerCredentialId())) == minActive) {
                leastActive.add(credential);
            }
        }
```

- [ ] **Step 4: 检查 import 是否仍需要 `Comparator`**

改造后 `selectChannel` 的 `channelIds.sort(Comparator.naturalOrder())`（第 54 行）仍在使用 `Comparator`，故保留 `import java.util.Comparator;`，不删除。

- [ ] **Step 5: 运行回归测试**

Run: `./mvnw -pl fluxora-gateway test -Dtest=UpstreamDispatchPlannerTest`
Expected: PASS（5 个测试用例全部通过：tier 隔离、channel 平衡、credential 平衡、排除凭证、排除 Channel 凭证绑定）

- [ ] **Step 6: Commit**

```bash
git add fluxora-gateway/src/main/java/io/fluxora/gateway/relay/scheduling/UpstreamDispatchPlanner.java
git commit -m "refactor: UpstreamDispatchPlanner tier/channel/credential 选择链转为显式循环"
```

---

## Task 2: RouteTargetSelector — stream 流转显式循环

**Files:**
- Modify: `fluxora-gateway/src/main/java/io/fluxora/gateway/route/RouteTargetSelector.java:24-28`
- Test（回归，不改）: 无直接单测，依赖 `GatewayRuntimeBehaviorTest` 间接覆盖路由选择

本任务改造 `select` 方法的 priority 最小值 + group 过滤 + totalWeight 求和。加权随机的 `ticket` 累减循环（第 30-35 行）已是显式循环，保留不动。

- [ ] **Step 1: 改造 `select` 的 priority 最小值 + group 过滤 + totalWeight 求和（第 24-28 行）**

把：
```java
        int priority = eligible.stream().map(target -> target.getInteger("priority", Integer.MAX_VALUE))
                .min(Comparator.naturalOrder()).orElse(Integer.MAX_VALUE);
        List<JsonObject> group = eligible.stream()
                .filter(target -> target.getInteger("priority", Integer.MAX_VALUE) == priority).toList();
        long totalWeight = group.stream().mapToLong(target -> target.getInteger("weight", 0)).sum();
```
替换为：
```java
        // 选取最高可用优先级（数值越小优先级越高），仅在该优先级组内进行加权随机
        int priority = Integer.MAX_VALUE;
        for (JsonObject target : eligible) {
            priority = Math.min(priority, target.getInteger("priority", Integer.MAX_VALUE));
        }
        // 收集当前最高优先级组内的目标，低优先级目标仅在当前组无可用目标时由外层空判定处理
        List<JsonObject> group = new ArrayList<>();
        for (JsonObject target : eligible) {
            if (target.getInteger("priority", Integer.MAX_VALUE) == priority) {
                group.add(target);
            }
        }
        // 累加该组内全部目标的权重，作为加权随机的总区间
        long totalWeight = 0L;
        for (JsonObject target : group) {
            totalWeight += target.getInteger("weight", 0);
        }
```

- [ ] **Step 2: 删除不再使用的 `Comparator` import**

改造后 `RouteTargetSelector` 不再使用 `Comparator`，删除第 7 行：
```java
import java.util.Comparator;
```

- [ ] **Step 3: 运行回归测试**

Run: `./mvnw -pl fluxora-gateway test -Dtest=GatewayRuntimeBehaviorTest`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add fluxora-gateway/src/main/java/io/fluxora/gateway/route/RouteTargetSelector.java
git commit -m "refactor: RouteTargetSelector 优先级与加权选择链转为显式循环"
```

---

## Task 3: RuntimeL1Caches.verifyHotManifestVersions — Stream.concat 嵌套转显式循环

**Files:**
- Modify: `fluxora-gateway/src/main/java/io/fluxora/gateway/runtime/RuntimeL1Caches.java:106-120`
- Test（回归，不改）: 无直接单测，由 `GatewayRuntimeBehaviorTest` 间接覆盖

本任务改造 `verifyHotManifestVersions`：6 层 `Stream.concat` 嵌套改为按缓存逐个遍历，通过"剩余配额"在缓存间传递保留原 `.limit(limit)` 的惰性截断语义（达到 limit 立即停止，不一次性物化全部缓存条目）。`onSuccess/onFailure` 回调体加中文注释。

- [ ] **Step 1: 改造 `verifyHotManifestVersions` 方法体（第 106-120 行）**

把：
```java
    public void verifyHotManifestVersions(int limit) {
        Stream.concat(Stream.concat(apiKeys.synchronous().asMap().values().stream(), users.synchronous().asMap().values().stream()),
                        Stream.concat(tenants.synchronous().asMap().values().stream(),
                                Stream.concat(routes.synchronous().asMap().values().stream(),
                                        Stream.concat(catalogs.synchronous().asMap().values().stream(), credentials.synchronous().asMap().values().stream()))))
                .limit(limit)
                .forEach(snapshot -> {
                    metrics.redisSnapshotRead.incrementAndGet();
                    source.manifestVersion(snapshot.scopeType(), snapshot.scopeKey()).onSuccess(version -> {
                        if (version > snapshot.runtimeVersion()) {
                            invalidate(snapshot.scopeType(), snapshot.scopeKey());
                        }
                    }).onFailure(ignored -> metrics.redisReadFailure.incrementAndGet());
                });
    }
```
替换为：
```java
    public void verifyHotManifestVersions(int limit) {
        // 按六类缓存的声明顺序逐个核对热点快照，剩余配额在缓存间传递；
        // 配额耗尽立即停止，保持与原 Stream.concat(...).limit(limit) 一致的惰性截断语义，
        // 避免一次性物化全部缓存条目造成内存与 CPU 浪费。
        int remaining = limit;
        remaining = checkCacheManifests(apiKeys, remaining);
        if (remaining <= 0) return;
        remaining = checkCacheManifests(users, remaining);
        if (remaining <= 0) return;
        remaining = checkCacheManifests(tenants, remaining);
        if (remaining <= 0) return;
        remaining = checkCacheManifests(routes, remaining);
        if (remaining <= 0) return;
        remaining = checkCacheManifests(catalogs, remaining);
        if (remaining <= 0) return;
        checkCacheManifests(credentials, remaining);
    }

    /**
     * 核对单个缓存内热点快照的 Manifest 版本，返回本次调用后剩余配额。
     * 配额耗尽立即停止遍历，与原 limit 截断语义一致；该方法非阻塞，仅发起异步版本核对。
     */
    private int checkCacheManifests(AsyncCache<String, RuntimeSnapshot> cache, int budget) {
        if (budget <= 0) return 0;
        int remaining = budget;
        for (RuntimeSnapshot snapshot : cache.synchronous().asMap().values()) {
            if (remaining <= 0) break;
            remaining--;
            metrics.redisSnapshotRead.incrementAndGet();
            source.manifestVersion(snapshot.scopeType(), snapshot.scopeKey())
                    // Redis 返回的 Manifest 版本高于本地缓存版本时，说明运行时已发布新版本，立即失效本地条目
                    .onSuccess(version -> {
                        if (version > snapshot.runtimeVersion()) {
                            invalidate(snapshot.scopeType(), snapshot.scopeKey());
                        }
                    })
                    // Redis 故障时保留未过硬 TTL 的本地值，下次过期读取仍会失败关闭，避免把所有热请求同步打到 Redis
                    .onFailure(ignored -> metrics.redisReadFailure.incrementAndGet());
        }
        return remaining;
    }
```

- [ ] **Step 2: 更新 import**

`Stream` 不再使用，删除第 11 行：
```java
import java.util.stream.Stream;
```
（`AsyncCache` 已在 import 中，`RuntimeSnapshot` 同包无需 import；无需新增 `ArrayList`/`List` import，本改造不再物化快照列表。）

- [ ] **Step 3: 运行回归测试**

Run: `./mvnw -pl fluxora-gateway test -Dtest=GatewayRuntimeBehaviorTest`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add fluxora-gateway/src/main/java/io/fluxora/gateway/runtime/RuntimeL1Caches.java
git commit -m "refactor: RuntimeL1Caches.verifyHotManifestVersions 的 Stream.concat 嵌套转为显式循环"
```

---

## Task 4: RelayAttemptCoordinator — Future 链抽命名方法

**Files:**
- Modify: `fluxora-gateway/src/main/java/io/fluxora/gateway/relay/orchestration/RelayAttemptCoordinator.java:63-73`
- Test（回归，不改）: `fluxora-gateway/src/test/java/io/fluxora/gateway/relay/orchestration/ResponseCommitBarrierTest.java`

本任务改造 `executeNext`：把 `compose` lambda 内"执行单次 Attempt + 释放租约"抽成 `private` 方法 `executeSingleAttempt`。`compose/eventually/onSuccess/onFailure` 顺序与异步语义不变。

- [ ] **Step 1: 改造 `executeNext` 方法体（第 63-73 行）**

把：
```java
    private void executeNext(RelayAttemptContext context, AttemptExecutor executor, Promise<Void> promise) {
        DispatchPlan plan = context.consumeFirstPlan();
        Future<DispatchPlan> planned = plan == null
                ? planner.planAndAcquire(context.routeSnapshot(), context.exclusions(), context.requestId(), context.nextAttemptId())
                : Future.succeededFuture(plan);
        planned.compose(currentPlan -> {
            AttemptStateMachine stateMachine = new AttemptStateMachine();
            return executor.execute(currentPlan, stateMachine)
                    .eventually(() -> planner.release(currentPlan.lease()));
        }).onSuccess(promise::complete).onFailure(error -> handleFailure(context, executor, promise, error));
    }
```
替换为：
```java
    private void executeNext(RelayAttemptContext context, AttemptExecutor executor, Promise<Void> promise) {
        DispatchPlan plan = context.consumeFirstPlan();
        Future<DispatchPlan> planned = plan == null
                ? planner.planAndAcquire(context.routeSnapshot(), context.exclusions(), context.requestId(), context.nextAttemptId())
                : Future.succeededFuture(plan);
        planned.compose(currentPlan -> executeSingleAttempt(currentPlan, executor))
                .onSuccess(promise::complete)
                .onFailure(error -> handleFailure(context, executor, promise, error));
    }

    /** 执行单次 Attempt：独立状态机驱动请求写入、上游响应与提交屏障判定，并在结束后释放该次租约。 */
    private Future<Void> executeSingleAttempt(DispatchPlan currentPlan, AttemptExecutor executor) {
        // 每次执行独立的状态机：单次 Attempt 的请求写入、上游响应、提交屏障判定互不串扰
        AttemptStateMachine stateMachine = new AttemptStateMachine();
        return executor.execute(currentPlan, stateMachine)
                // 无论本次执行成功或失败，都必须释放该 Attempt 持有的调度租约，避免租约泄漏阻塞后续调度
                .eventually(() -> planner.release(currentPlan.lease()));
    }
```

- [ ] **Step 2: 运行回归测试**

Run: `./mvnw -pl fluxora-gateway test -Dtest=ResponseCommitBarrierTest`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add fluxora-gateway/src/main/java/io/fluxora/gateway/relay/orchestration/RelayAttemptCoordinator.java
git commit -m "refactor: RelayAttemptCoordinator 执行链抽取命名方法并补充中文注释"
```

---

## Task 5: GatewayRouteResolver — Future 链抽命名方法

**Files:**
- Modify: `fluxora-gateway/src/main/java/io/fluxora/gateway/route/GatewayRouteResolver.java:21-46`
- Test（回归，不改）: `fluxora-gateway/src/test/java/io/fluxora/gateway/RuntimeCredentialResolverTest.java`、`GatewayRuntimeBehaviorTest`

本任务改造 `resolve` 与 `resolveRouteSnapshot`：把 `.map` 内的路由选择抽成 `selectFromRoute`，`.map/.recover` 体内业务逻辑加中文注释。

- [ ] **Step 1: 改造 `resolve` 方法体（第 21-29 行）**

把：
```java
    public Future<RouteSelection> resolve(long tenantId, String inboundProtocol, String tenantModelCode,
                                           boolean streamingRequested) {
        return resolveRouteSnapshot(tenantId, inboundProtocol, tenantModelCode, streamingRequested)
                .map(route -> {
                    RouteSelection selection = selector.select(route.getJsonArray("targets"), inboundProtocol);
                    if (selection == null) throw GatewayFailure.modelUnavailable();
                    return selection.withRouteSnapshot(route);
                });
    }
```
替换为：
```java
    public Future<RouteSelection> resolve(long tenantId, String inboundProtocol, String tenantModelCode,
                                           boolean streamingRequested) {
        return resolveRouteSnapshot(tenantId, inboundProtocol, tenantModelCode, streamingRequested)
                .map(route -> selectFromRoute(route, inboundProtocol));
    }

    /** 在已校验的路由执行包内选择目标，无可用目标时抛模型不可用，统一进入失败处理。 */
    private RouteSelection selectFromRoute(JsonObject route, String inboundProtocol) {
        RouteSelection selection = selector.select(route.getJsonArray("targets"), inboundProtocol);
        // 路由执行包可用但选择器未能挑出任何目标，说明全部目标不可调用，按模型不可用处理
        if (selection == null) throw GatewayFailure.modelUnavailable();
        return selection.withRouteSnapshot(route);
    }
```

- [ ] **Step 2: 改造 `resolveRouteSnapshot` 方法体（第 32-46 行）**

把：
```java
    public Future<JsonObject> resolveRouteSnapshot(long tenantId, String inboundProtocol, String tenantModelCode,
                                                   boolean streamingRequested) {
        if (tenantModelCode == null || tenantModelCode.isBlank() || tenantModelCode.length() > 128) {
            return Future.failedFuture(GatewayFailure.modelUnavailable());
        }
        String scopeKey = RouteScopeKey.of(tenantId, inboundProtocol, tenantModelCode);
        return caches.route(scopeKey).map(snapshot -> {
            JsonObject route = snapshot.payload();
            if (!modelUsable(route, tenantId, inboundProtocol, tenantModelCode, streamingRequested)) {
                throw GatewayFailure.modelUnavailable();
            }
            return route;
        }).recover(error -> Future.failedFuture(error instanceof GatewayFailure
                ? error : GatewayFailure.runtimeUnavailable()));
    }
```
替换为：
```java
    public Future<JsonObject> resolveRouteSnapshot(long tenantId, String inboundProtocol, String tenantModelCode,
                                                   boolean streamingRequested) {
        // 模型码缺失或超长直接判定模型不可用，避免构造无效 Scope Key 命中缓存
        if (tenantModelCode == null || tenantModelCode.isBlank() || tenantModelCode.length() > 128) {
            return Future.failedFuture(GatewayFailure.modelUnavailable());
        }
        String scopeKey = RouteScopeKey.of(tenantId, inboundProtocol, tenantModelCode);
        return caches.route(scopeKey)
                .map(snapshot -> validateRouteSnapshot(snapshot.payload(), tenantId, inboundProtocol,
                        tenantModelCode, streamingRequested))
                // 仅 GatewayFailure 是业务可对外语义，其余异常统一收敛为运行时不可用，避免泄露内部细节
                .recover(error -> Future.failedFuture(error instanceof GatewayFailure
                        ? error : GatewayFailure.runtimeUnavailable()));
    }

    /** 校验路由执行包：租户、协议、模型码、启用状态、价格有效期与流式能力任一不满足即判定模型不可用。 */
    private JsonObject validateRouteSnapshot(JsonObject route, long tenantId, String inboundProtocol,
                                             String tenantModelCode, boolean streamingRequested) {
        if (!modelUsable(route, tenantId, inboundProtocol, tenantModelCode, streamingRequested)) {
            throw GatewayFailure.modelUnavailable();
        }
        return route;
    }
```

- [ ] **Step 3: 运行回归测试**

Run: `./mvnw -pl fluxora-gateway test -Dtest=RuntimeCredentialResolverTest,GatewayRuntimeBehaviorTest`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add fluxora-gateway/src/main/java/io/fluxora/gateway/route/GatewayRouteResolver.java
git commit -m "refactor: GatewayRouteResolver 路由解析链抽取命名方法并补充中文注释"
```

---

## Task 6: RedisRuntimeSnapshotSource — Future 链抽命名方法

**Files:**
- Modify: `fluxora-gateway/src/main/java/io/fluxora/gateway/runtime/RedisRuntimeSnapshotSource.java:26-41`
- Test（回归，不改）: `GatewayRuntimeBehaviorTest`

本任务改造 `load`：把 `compose` 内"读快照 + 校验 + 构造"抽成 `private` 方法 `parseAndValidateSnapshot`。`compose/map` 顺序与异步语义不变。

- [ ] **Step 1: 改造 `load` 方法体（第 26-41 行）**

把：
```java
    @Override
    public Future<RuntimeSnapshot> load(RuntimeScopeType scopeType, String scopeKey) {
        return get(manifestKey(scopeType, scopeKey))
                .compose(manifestText -> {
                    JsonObject manifest = parse(manifestText);
                    long version = validatedManifestVersion(manifest);
                    return get(snapshotKey(scopeType, scopeKey, version)).map(snapshotText -> {
                        JsonObject snapshot = parse(snapshotText);
                        if (snapshot.getInteger("schemaVersion", -1) != SCHEMA_VERSION
                                || snapshot.getLong("runtimeVersion", -1L) != version
                                || snapshot.getString("generatedAt") == null) {
                            throw GatewayFailure.runtimeUnavailable();
                        }
                        return new RuntimeSnapshot(scopeType, scopeKey, version, snapshot);
                    });
                });
    }
```
替换为：
```java
    @Override
    public Future<RuntimeSnapshot> load(RuntimeScopeType scopeType, String scopeKey) {
        return get(manifestKey(scopeType, scopeKey))
                .compose(manifestText -> {
                    // 先读 Manifest 解析当前激活版本号，再据此读取对应的不可变快照，确保读到版本一致的数据
                    JsonObject manifest = parse(manifestText);
                    long version = validatedManifestVersion(manifest);
                    return get(snapshotKey(scopeType, scopeKey, version))
                            .map(snapshotText -> parseAndValidateSnapshot(scopeType, scopeKey, version, snapshotText));
                });
    }

    /** 解析并校验快照正文：schema 版本、运行时版本、生成时间任一不匹配即判定运行时不可用。 */
    private RuntimeSnapshot parseAndValidateSnapshot(RuntimeScopeType scopeType, String scopeKey, long version,
                                                     String snapshotText) {
        JsonObject snapshot = parse(snapshotText);
        if (snapshot.getInteger("schemaVersion", -1) != SCHEMA_VERSION
                || snapshot.getLong("runtimeVersion", -1L) != version
                || snapshot.getString("generatedAt") == null) {
            throw GatewayFailure.runtimeUnavailable();
        }
        return new RuntimeSnapshot(scopeType, scopeKey, version, snapshot);
    }
```

- [ ] **Step 2: 运行回归测试**

Run: `./mvnw -pl fluxora-gateway test -Dtest=GatewayRuntimeBehaviorTest`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add fluxora-gateway/src/main/java/io/fluxora/gateway/runtime/RedisRuntimeSnapshotSource.java
git commit -m "refactor: RedisRuntimeSnapshotSource.load 快照校验抽取命名方法并补充中文注释"
```

---

## Task 7: RelayEventPublisher — Future 链补充中文注释

**Files:**
- Modify: `fluxora-gateway/src/main/java/io/fluxora/gateway/observability/RelayEventPublisher.java:47-56,62-82`
- Test（回归，不改）: `fluxora-gateway/src/test/java/io/fluxora/gateway/observability/RelayEventPublisherTest.java`

本任务不抽方法（`onSuccess/onFailure` 体已较短），仅补充中文注释，说明各分支的业务意图与安全边界。链结构不变。

- [ ] **Step 1: 为 `publishFields` 的 onSuccess/onFailure 体补充注释（第 47-56 行）**

把：
```java
    private void publishFields(Map<String, String> fields, RelayObservationEvent observation) {
        client.append(fields).onSuccess(ignored -> {
                    metrics.relayEventsProduced.incrementAndGet();
                    if (observation != null) recordObservationMetrics(observation);
                })
                .onFailure(ignored -> {
                    metrics.relayEventsPublishFailed.incrementAndGet();
                    enqueue(fields, observation);
                });
    }
```
替换为：
```java
    private void publishFields(Map<String, String> fields, RelayObservationEvent observation) {
        client.append(fields)
                // 投递成功：计入生产计数；请求日志类事件需要进一步记录用量与计价观测指标
                .onSuccess(ignored -> {
                    metrics.relayEventsProduced.incrementAndGet();
                    if (observation != null) recordObservationMetrics(observation);
                })
                // 投递失败：绝不向调用者抛出 Redis 异常，仅计入失败计数并进入内存有界重试队列
                .onFailure(ignored -> {
                    metrics.relayEventsPublishFailed.incrementAndGet();
                    enqueue(fields, observation);
                });
    }
```

- [ ] **Step 2: 为 `retryPending` 的 onSuccess/onFailure 体补充注释（第 62-82 行）**

把：
```java
    public void retryPending() {
        PendingEvent next = pending.peekFirst();
        if (next == null) {
            return;
        }
        metrics.relayEventsRetry.incrementAndGet();
        client.append(next.fields()).onSuccess(ignored -> {
            pending.removeFirstOccurrence(next);
            metrics.relayEventsProduced.incrementAndGet();
            if (next.observation() != null) recordObservationMetrics(next.observation());
            refreshPendingMetric();
        }).onFailure(ignored -> {
            metrics.relayEventsPublishFailed.incrementAndGet();
            next.incrementAttempts();
            if (next.attempts() >= maxAttempts) {
                pending.removeFirstOccurrence(next);
                metrics.relayEventsDropped.incrementAndGet();
            }
            refreshPendingMetric();
        });
    }
```
替换为：
```java
    public void retryPending() {
        // 每次只重试队首一个事件，防止 Redis 恢复瞬间在 Event Loop 上突发大量命令
        PendingEvent next = pending.peekFirst();
        if (next == null) {
            return;
        }
        metrics.relayEventsRetry.incrementAndGet();
        client.append(next.fields())
                // 重试成功：从队列移除并计入生产计数，请求日志类事件补充观测指标
                .onSuccess(ignored -> {
                    pending.removeFirstOccurrence(next);
                    metrics.relayEventsProduced.incrementAndGet();
                    if (next.observation() != null) recordObservationMetrics(next.observation());
                    refreshPendingMetric();
                })
                // 重试失败：累加重试次数，达到上限则丢弃并计入丢弃计数，避免单条毒消息长期占用队列
                .onFailure(ignored -> {
                    metrics.relayEventsPublishFailed.incrementAndGet();
                    next.incrementAttempts();
                    if (next.attempts() >= maxAttempts) {
                        pending.removeFirstOccurrence(next);
                        metrics.relayEventsDropped.incrementAndGet();
                    }
                    refreshPendingMetric();
                });
    }
```

- [ ] **Step 3: 运行回归测试**

Run: `./mvnw -pl fluxora-gateway test -Dtest=RelayEventPublisherTest`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add fluxora-gateway/src/main/java/io/fluxora/gateway/observability/RelayEventPublisher.java
git commit -m "refactor: RelayEventPublisher 投递与重试链补充中文注释"
```

---

## Task 8: 全量回归验证

**Files:**
- 无修改，仅运行测试

- [ ] **Step 1: 运行 gateway 模块全量测试**

Run: `./mvnw -pl fluxora-gateway test`
Expected: 全部 PASS，0 失败

- [ ] **Step 2: 确认无测试文件被修改**

Run: `git status --short -- fluxora-gateway/src/test`
Expected: 无输出（测试目录无变更）

- [ ] **Step 3: 确认本次改造文件范围**

Run: `git log --oneline -8`
Expected: 看到 Task 1-7 的 7 个 `refactor:` 提交，且仅改动 7 个 main 源文件，无测试/其他模块改动。

若全量测试失败，回退到对应任务的提交，对照原逻辑逐行核对谓词与比较条件，修复后重新提交（amend 或新提交）。

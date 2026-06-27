# Gateway 链式调用可调试性改造设计

- 日期：2026-06-27
- 模块：`fluxora-gateway`
- 类型：重构（refactor，不改变外部行为）

## 1. 背景与目标

`fluxora-gateway` 模块中存在两类链式调用，导致调试困难：

1. **Stream 流**：集中在 `UpstreamDispatchPlanner`、`RouteTargetSelector`、`RuntimeL1Caches`，中间值不可见、异常栈帧无业务方法名、`Stream.concat` 多层嵌套难以阅读。
2. **Vert.x Future 链**：遍布 `GatewayRouteResolver`、`RedisRuntimeSnapshotSource`、`RelayAttemptCoordinator`、`RelayEventPublisher` 等，lambda 体内难以设置断点，异步栈帧缺少业务阶段名称。

目标：在不改变执行逻辑、不改变类设计、不降低执行性能的前提下，把上述链式调用改造为可逐步调试、中间值可见、栈帧可读的形态，并补充有业务意义的中文注释。

## 2. 红线（不可破约束）

按用户要求与 `AGENT.md`，本次改造遵守以下边界：

1. **执行逻辑不变** — 不改任何判断分支、调度算法、加权随机/轮询的确定性、Future 组合顺序、租约获取/释放时序、成功/失败分支语义。
2. **类设计不变** — 不新增/删除类、不改公共方法签名、不改构造参数、不改字段。改造只发生在方法体内部（A 类）/ 抽取 `private` 方法（B 类）。
3. **性能不降** — stream→显式循环去掉 Stream 流水线开销（略优或持平）；Future 链仅做"抽取命名方法 + 注释"，不引入额外 Future 跳数、不在热路径新增对象分配、不改异步语义。
4. **遵循中文注释规范** — 实体、关键字段、复杂逻辑、易错逻辑必须具备有业务意义的中文注释，不机械重复代码字面含义。

## 3. 改造范围

### 3.1 A 类：Stream 流 → 显式循环 + 命名中间变量（3 文件）

手法：把 `stream().filter().map().collect()` / `mapToLong().min()` / `Stream.concat` 嵌套改写为 `for` 循环，每步结果存入有业务含义的局部变量，并加中文注释说明该步骤的业务意图。**谓词与比较条件原样保留**，只改形态。

#### 3.1.1 `UpstreamDispatchPlanner`

- `planAndAcquire`（第 38-39 行）：tier 最小值计算 + tier 候选过滤，改为两次 `for` 循环。
- `selectChannel`（第 55-58 行）：minActive 计算 + leastActive 过滤，改为 `for` 循环。
- `selectCredential`（第 73-76 行）：minActive 计算 + leastActive 过滤，改为 `for` 循环。

#### 3.1.2 `RouteTargetSelector`

- `select`（第 24-28 行）：priority 最小值 + group 过滤 + totalWeight 求和，改为 `for` 循环。加权随机的 `ticket` 累减循环已是显式循环，保留不动。

#### 3.1.3 `RuntimeL1Caches`

- `verifyHotManifestVersions`（第 107-111 行）：6 层 `Stream.concat` 嵌套，改为依次 `for` 遍历 6 个缓存的 `asMap().values()`，`limit` 截断语义通过计数器保留。

### 3.2 B 类：Vert.x Future 链 → 抽取命名方法 + 中文注释（4 文件）

手法：把长 lambda 体抽成 `private` 命名方法，使异步栈帧出现业务方法名、可在方法入口/出口下断点。链的 `compose/eventually/onSuccess/onFailure` 顺序与异步语义完全不变，不在热路径新增闭包对象。

#### 3.2.1 `RelayAttemptCoordinator`

- `executeNext`（第 68-72 行）：`compose` 内的"执行单次 Attempt + 释放租约"抽成 `executeSingleAttempt`。

#### 3.2.2 `GatewayRouteResolver`

- `resolve`（第 23-29 行）：`.map` 内的"路由选择 + 空选择兜底"抽成 `selectFromRoute`。
- `resolveRouteSnapshot`（第 38-45 行）：`.map` + `.recover` 体内业务逻辑加中文注释，必要时抽小方法。

#### 3.2.3 `RedisRuntimeSnapshotSource`

- `load`（第 26-41 行）：`compose(map)` 嵌套拆分，`map` 内的快照校验抽成 `parseAndValidateSnapshot`。

#### 3.2.4 `RelayEventPublisher`

- `publishFields`（第 47-56 行）：`onSuccess/onFailure` 体内业务逻辑加注释。
- `retryPending`（第 62-82 行）：`onSuccess/onFailure` 体内业务逻辑加注释。

### 3.3 不改动的部分（明确边界）

- `UpstreamHttpClient.post`、`GatewayHttpServer` 启动链、`GatewayModelCatalog`：仅 1 行短链，改造收益极低且增加噪声。
- Optional 链：不在本次范围（用户决策）。
- 所有测试文件不改，用现有单测做回归校验。

## 4. 改造示例

### A 类示例 — `UpstreamDispatchPlanner.planAndAcquire`

```java
// 改造前：中间值不可见，栈帧无业务名
int tier = candidates.stream().map(DispatchCandidate::priorityTier).min(Comparator.naturalOrder()).orElse(Integer.MAX_VALUE);
List<DispatchCandidate> tierCandidates = candidates.stream().filter(c -> c.priorityTier() == tier).toList();

// 改造后：每步命名，可逐步断点查看；谓词与比较条件不变
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

### B 类示例 — `RelayAttemptCoordinator.executeNext`

```java
// 改造前：lambda 内部嵌套，断点难定位到具体业务阶段
planned.compose(currentPlan -> {
    AttemptStateMachine stateMachine = new AttemptStateMachine();
    return executor.execute(currentPlan, stateMachine)
            .eventually(() -> planner.release(currentPlan.lease()));
}).onSuccess(promise::complete).onFailure(error -> handleFailure(context, executor, promise, error));

// 改造后：抽出命名方法，栈帧/断点可读；compose/eventually 顺序与异步语义不变
planned.compose(currentPlan -> executeSingleAttempt(currentPlan, executor))
        .onSuccess(promise::complete)
        .onFailure(error -> handleFailure(context, executor, promise, error));

private Future<Void> executeSingleAttempt(DispatchPlan currentPlan, AttemptExecutor executor) {
    // 每次执行独立的状态机：单次 Attempt 的请求写入、上游响应、提交屏障判定互不串扰
    AttemptStateMachine stateMachine = new AttemptStateMachine();
    return executor.execute(currentPlan, stateMachine)
            // 无论本次执行成功或失败，都必须释放该 Attempt 持有的调度租约，避免租约泄漏阻塞后续调度
            .eventually(() -> planner.release(currentPlan.lease()));
}
```

## 5. 风险与对策

| 风险 | 对策 |
|---|---|
| stream→循环若手抖改了比较/过滤条件，改变调度结果 | 每处只改"形态"不改"谓词"；改完逐文件对照原逻辑核对；用现有调度单测验证加权平衡与排除逻辑 |
| Future 抽方法可能引入额外对象分配（lambda 捕获 `this`） | 抽出的方法只用已有参数，不在热路径新建闭包对象；`RelayAttemptCoordinator` 抽方法仅多一次方法调用，无新分配 |
| 注释机械重复代码字面含义 | 注释说明业务意图、约束、边界、状态流转原因或安全考虑，符合 AGENT.md 中文注释规范 |

## 6. 回归验证

改造后运行 `fluxora-gateway` 模块现有单测，确保：

- 调度加权/轮询的确定性（`UpstreamDispatchPlannerTest` 的 tier 隔离、channel 平衡、credential 平衡、排除逻辑）。
- 租约获取/释放时序（`ResponseCommitBarrierTest`、`RelayAttemptCoordinator` 相关）。
- Future 成功/失败分支语义（`RelayEventPublisherTest`、`RelayObservationEventTest`）。

无新增测试，不修改测试文件。

## 7. Git 提交

- 类型：`refactor`
- 描述：重构 gateway 链式调用，stream 流转为显式循环、Future 链抽取命名方法并补充中文注释，提升可调试性，不改执行逻辑与性能
- 提交前执行 `git status --short`、`git diff --check`，按文件 `git add`，不提交无关文件

# Native Relay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Fluxora Gateway 实现安全的 OpenAI / Anthropic 原生同协议中继。

**Architecture:** Platform 将普通路由快照和敏感凭证运行时快照分离投影；Gateway 使用 Redis、Caffeine 和 Vert.x 完成鉴权、选路、临时解密与中继。RelayService 只做公共编排，两个 Handler 收敛协议差异。

**Tech Stack:** Java 25, Spring Boot, MyBatis XML, PostgreSQL/Flyway, Redis, Caffeine 3.2.1, Vert.x 5.1.2, JUnit 5, Testcontainers.

---

### Task 1: 运行时敏感凭证快照

**Files:**
- Create: `fluxora-platform/src/main/resources/db/migration/V13__上游敏感凭证运行时快照.sql`
- Modify: `fluxora-platform/src/main/java/io/fluxora/platform/runtime/RuntimeScopeType.java`
- Modify: `fluxora-platform/src/main/java/io/fluxora/platform/runtime/RuntimeScope.java`
- Modify: `fluxora-platform/src/main/java/io/fluxora/platform/runtime/RuntimeImpactResolver.java`
- Modify: `fluxora-platform/src/main/java/io/fluxora/platform/runtime/RuntimeSnapshotBuilder.java`
- Modify: `fluxora-platform/src/main/java/io/fluxora/platform/runtime/mapper/RuntimeMapper.java`
- Modify: `fluxora-platform/src/main/resources/mapper/RuntimeMapper.xml`
- Test: `fluxora-platform/src/test/java/io/fluxora/platform/runtime/RuntimeProjectionIntegrationTest.java`

- [ ] Add a failing projection test asserting that `TENANT_MODEL_ROUTE` has only credential IDs, versions, auth types and statuses, while `UPSTREAM_CREDENTIAL` has runtime ciphertext but never plaintext or the Platform master key.
- [ ] Add the V13 schema comments and runtime version support for the new Scope.
- [ ] Build `credentialRefs` in the route snapshot and a separate versioned credential snapshot from a single MyBatis read per Scope.
- [ ] Extend impact resolution so credential and binding changes refresh both the credential Scope and affected routes.
- [ ] Run `mvn -pl fluxora-platform -Dtest=RuntimeProjectionIntegrationTest test` and commit the first phase.

### Task 2: Gateway credential and transport foundations

**Files:**
- Create: `fluxora-gateway/src/main/java/io/fluxora/gateway/credential/RuntimeCredentialResolver.java`
- Create: `fluxora-gateway/src/main/java/io/fluxora/gateway/transport/UpstreamHttpClient.java`
- Create: `fluxora-gateway/src/main/java/io/fluxora/gateway/transport/UpstreamUrlValidator.java`
- Modify: `fluxora-gateway/src/main/java/io/fluxora/gateway/runtime/RuntimeScopeType.java`
- Modify: `fluxora-gateway/src/main/java/io/fluxora/gateway/runtime/RuntimeL1Caches.java`
- Modify: `fluxora-gateway/src/main/java/io/fluxora/gateway/GatewayRuntimeConfig.java`
- Modify: `fluxora-gateway/src/main/java/io/fluxora/gateway/GatewayRuntime.java`
- Test: `fluxora-gateway/src/test/java/io/fluxora/gateway/RuntimeCredentialResolverTest.java`

- [ ] Add failing tests for version mismatch, disabled credential, BEARER/X_API_KEY/NONE headers and no plaintext cache.
- [ ] Add gateway-only runtime-key validation and runtime ciphertext decryption; do not add JDBC or Platform dependencies.
- [ ] Add URI validation, profile-restricted local allowlist and Vert.x request timeout/cancellation primitives.
- [ ] Run the focused Gateway test suite and commit the second phase.

### Task 3: Protocol handlers and HTTP endpoint

**Files:**
- Create: `fluxora-gateway/src/main/java/io/fluxora/gateway/relay/RelayService.java`
- Create: `fluxora-gateway/src/main/java/io/fluxora/gateway/relay/RelayHandler.java`
- Create: `fluxora-gateway/src/main/java/io/fluxora/gateway/relay/OpenAiRelayHandler.java`
- Create: `fluxora-gateway/src/main/java/io/fluxora/gateway/relay/AnthropicRelayHandler.java`
- Modify: `fluxora-gateway/src/main/java/io/fluxora/gateway/GatewayHttpServer.java`
- Modify: `fluxora-gateway/src/main/java/io/fluxora/gateway/GatewayFailure.java`
- Test: `fluxora-gateway/src/test/java/io/fluxora/gateway/OpenAiRelayHandlerTest.java`
- Test: `fluxora-gateway/src/test/java/io/fluxora/gateway/AnthropicRelayHandlerTest.java`
- Test: `fluxora-gateway/src/test/java/io/fluxora/gateway/GatewayRelayIntegrationTest.java`

- [ ] Write focused failing tests for JSON model replacement, response model restoration, protocol-native error envelopes and SSE event model restoration.
- [ ] Implement the minimal common RelayService and one protocol switch; reject cross-protocol targets before transport.
- [ ] Preserve unknown JSON fields, strip unsafe client headers, use only resolved upstream credentials, stream without aggregation, and cancel upstream when downstream closes.
- [ ] Run `mvn -pl fluxora-gateway test` and commit OpenAI then Anthropic phases.

### Task 4: Regression, documentation and local verification

**Files:**
- Modify: `README.md`
- Modify: `docs/runtime-config-architecture.md`
- Modify: `docs/runtime-refresh-coverage-matrix.md`
- Create: `docs/native-relay-architecture.md`
- Create: `docs/local-ollama-upstream-testing.md`
- Modify: `docker-compose.yml`, `.env.example`, `fluxora-platform/src/main/resources/application.yml`
- Modify: `fluxora-web/src/__tests__/tenant-model-management.spec.ts`

- [ ] Reproduce the pre-existing Vitest timeout with its focused test, then make the import assertion deterministic without weakening it.
- [ ] Document native endpoint limits, snapshot separation, local Ollama setup and safe test commands without committing local keys.
- [ ] Run backend tests, frontend tests/build, focused Gateway integration tests and available local Ollama checks.
- [ ] Run `git diff --check`, inspect explicit files, create Conventional Commit messages for each completed phase.

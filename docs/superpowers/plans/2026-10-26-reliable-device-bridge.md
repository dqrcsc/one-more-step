# Reliable Device Bridge Implementation Plan

> **For agentic workers:** Execute bounded tasks with independent workers and a final integration review. The referenced superpowers execution subskills are not installed; use the available collaboration tools directly, preserving this plan's interfaces and verification gates.

**Goal:** Repair the existing authentication, acknowledged delivery, GUI confirmation and approval paths identified by the approved source review.

**Architecture:** Preserve CLI/MCP → IPC → daemon → phone. Tighten trust and durable-success boundaries instead of introducing a new transport stack. Host, Android transport and Android GUI are separate work units with explicit interfaces.

**Tech Stack:** Bun/TypeScript, Kotlin/Compose/Coroutines/SQLite, Swift/CoreBluetooth.

**Spec:** `docs/plans/2026-10-26-reliable-device-bridge-design.md`

## Global Constraints

- No recursive/batch filesystem deletion; retain test fixtures.
- Preserve existing CLI/MCP names and binary frame layout.
- Legacy records without a pairing secret must re-pair.
- Never report delivery before receiver acknowledgement.
- No real phone actions, global setup or publishing during validation.

## Task 1: Host authentication and delivery

Files: `mac-host/src/transport/session-manager.ts`, `mac-host/src/inbox/inbox-manager.ts`, host regression tests.
Consumes current frame/RPC definitions. Produces validated stream completion and pipe-bound pending operations.

- [x] Add regression assertions for legacy rejection, untrusted/foreign response rejection, revocation and hash/length errors, e.g. `expect(manager.getCompletedStream(id)).toBeUndefined()` after a corrupted stream.
- [x] Reject invalid trust state before all business handlers; remove static/public-secret migration; revoke replaced pipes and pending operations.
- [x] Validate stream headers/chunks, await matching completion ACK on pushes, reject incomplete inbox submissions, separate same-name attachments and regenerate derived views.
- [x] Run `bun test mac-host/tests/reliability.test.ts` then `bun run typecheck`.

## Task 2: Android delivery and approval state

Files: `OmsTransportClient.kt`, `StreamRepository.kt`, `MainActivity.kt`, `ScopedStorageExecutor.kt`, Android policy tests.
Consumes stream ACK and approval expiry contract. Produces committed push ACKs, retryable outbox and acknowledged approval decisions.

- [x] Add pure-state tests where feasible: failed ACK leaves QUEUED; accepted ACK transitions DELIVERED; expired approval cannot dispatch.
- [x] Validate inbound stream hash/length/chunks before saving/ACK. Await matching inbox RPC success before changing QUEUED to DELIVERED; preserve retry state after failure.
- [x] Reply on the originating authenticated pipe. Remove legacy Mac ID/public-secret migration. Persist expiry/options and acknowledge decisions before terminal local state.
- [x] Enforce directory allowlists on content URIs and canonical directory boundaries.
- [x] Run Android JVM tests and `./gradlew :app:compileDebugKotlin --offline --no-daemon`.

## Task 3: Android GUI safety

Files: `OmsAccessibilityService.kt`, `SafetyStopOverlay.kt`, `SemanticTreeExtractor.kt`, accessibility XML, pure Kotlin safety tests.
Consumes observationId (required for node actions) and deadlineMs (required for every action). Produces observationId and fail-closed, cancellable, serialized actions.

- [x] Add regression tests for persistent stop, stale observation and deadline rejection in a small pure state helper if needed.
- [x] Deny unavailable confirmation UI; clean up cancelled confirmation. Add touch event interruption, latch emergency stop until local resume, serialize actions and validate observation/window state.
- [x] Execute Android UI work on main dispatcher and bound waits to deadline; never execute after cancellation.
- [x] Run Kotlin compile/tests. Record missing real-device acceptance.

## Task 4: Host approval, interface and local UI integration

Files: `oms-daemon.ts`, `ipc-client.ts`, `mcp/server.ts`, `cli/index.ts`, `inbox-web-dashboard.ts`, protocol types and tests.

- [x] Test fast approval response with a synchronous fake phone callback: `expect(result.approved).toBe(true)`.
- [x] Register approval before dispatch, validate callback/expiry, use matching bounded timeouts in MCP/CLI and forward GUI observation/deadline.
- [x] Reject hostile Origin/non-JSON mutations and arbitrary local file actions.
- [x] Update README/Skill/CHANGELOG with actual behavior and compatibility impact; preserve historical review documents.
- [x] Run complete Bun suite with protected preload, TypeScript/Swift checks, Android tests/compile and inspect scoped diff.

## Completion evidence

- [x] All applicable regression tests and compile checks pass.
- [x] Remaining encryption, BLE/WAN and real-device gaps are explicit.
- [x] No unrelated changes or prohibited cleanup; report branch and exact verification results.

Verification and remaining device acceptance: [verification-2026-10-26](../../verification-2026-10-26.md).

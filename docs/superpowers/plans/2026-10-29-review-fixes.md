# v1.5.5 Review Fixes Implementation Plan

> Execution: implement the already approved review fixes in this session, with independent Android and Mac lifecycle work; integrate and review before completion.

**Goal:** Fix all six defects identified in the 2026-10-28 review without deploying or changing phone state.
**Architecture:** Keep existing RPC interfaces. Preserve GUI emergency-stop semantics; refuse unverified hangup targets. Separate notification display and stop intents. Serialize mirror lifecycle and validate AirPods target before release. Package Neural TTS support in the repository with explicit fallback diagnostics.
**Tech Stack:** Kotlin/Android, TypeScript/Bun, Python edge-tts.
**Spec:** one-more-step-analysis/reviews/2026-10-28/review.md (adjacent analysis workspace); user approved the six fixes on 2026-10-29.

## Global Constraints

- Only task-related changes; no unrelated refactors, release/version bump or deployment.
- No real phone gestures, calls, audio playback or Bluetooth switching in automated tests.
- No bulk deletion. Preserve test evidence and existing user changes.
- Maintain compatibility with existing callers; report failure rather than invent successful results.

## Task 1: Android action boundaries

Files: OmsAccessibilityService.kt, GuiSessionGuard.kt and focused JVM regression tests.
- [x] Check emergency stop and session validity after acquiring guiMutex for mirror input; bounded queue lifetime, check safety again after suspend points where another action can occur.
- [x] Remove the fixed-coordinate hangup fallback. Require active call state and a verified hangup node, and confirm call completion or return a truthful failure/pending result.
- [x] Verify interrupted mirror and missing hangup-target cases fail closed; compile and run JVM tests.

## Task 2: Android notification actions

Files: NotificationExecutor.kt and focused tests.
- [x] Give full-screen display a distinct PendingIntent identity without EXTRA_STOP_TTS; keep user content-click stop and explicit stop button semantics.
- [x] Check both urgent TTS and ordinary notification flows; verify existing MainActivity stop handling remains compatible.

## Task 3: Mac lifecycle and results

Files: oms-daemon.ts and mac-host/tests review regression tests.
- [x] Reproduce offline AirPods false success with mocked session; refuse before releasing Mac and propagate release/phone errors.
- [x] Reproduce mirror start→stop→late start response; serialize/cancel stale lifecycle work and ensure closing final viewer during startup still stops capture.
- [x] Cover multiple viewers, start failure, stop failure and later restart without real phone access.

## Task 4: Deployable Neural TTS

Files: packaged Python script/requirements, Mac synthesis integration, setup script, README and tests.
- [x] Replace ~/.codex Skill import with repository-owned edge-tts wrapper and a documented local venv dependency installation.
- [x] Preserve Android-local fallback with explicit structured reason when provider unavailable or synthesis fails.
- [x] Verify missing dependency and fake-provider success without network or real TTS; keep text out of diagnostic logs.

## Integration verification

- [x] bun test; bun run typecheck.
- [x] Android testDebugUnitTest and assembleDebug with offline cached dependencies.
- [x] Review final diff and document test scope, remaining device verification and deployment status.

## Completion evidence

See `docs/review-fixes-2026-10-29.md`. Android full-screen PendingIntent behavior was verified by code inspection/compilation only; device acceptance remains explicit. No deployment or publication.

## Approved follow-up: offline exceptional paths and control safety

- [x] Verify session replacement, route switching, RPC timeout and late/duplicate responses using encrypted simulated peers.
- [x] Bind unlock, call and mirror requests to one authenticated physical route and preserve absolute deadlines.
- [x] Stop after failed unlock; remove legacy dialing/mirror input fallbacks and credential retries.
- [x] Guard each multi-step Android side effect and pause; verify queue expiration, emergency stop and cancellation with production Kotlin helpers.
- [x] Verify phone number, SIM and dialer target; remove pattern secret responses and require a known keyguard surface before credential input.
- [x] Re-run integrated Bun/TypeScript/Python/JVM checks and build a fresh Debug APK; record compatibility restrictions and remaining real-device acceptance.

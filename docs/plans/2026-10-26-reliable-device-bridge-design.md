# Reliable device bridge: approved remediation design

The user approved the preceding source review and its recommended order on 2026-10-26. This change implements the first stabilization batch in the existing checkout on `fix/reliable-device-bridge`.

## Chosen approach and tradeoffs

Use the existing single-daemon architecture and repair its state transitions. Alternatives were a transport rewrite (too broad before real-device evidence) and documentation-only downgrades (would leave reproducible correctness bugs). Keep current CLI/MCP names and the binary frame layout. Strict delivery acknowledgements require upgrading both host and phone; old phones must fail visibly instead of reporting unverified success. Legacy records without a pairing secret must re-pair; no public identifier or fixed string is accepted as authentication.

## Required behavior

1. Authentication: authenticated, currently trusted, current transport pipe required for business requests, responses and streams. Pending RPC/stream completions bind to the issuing pipe. Replace/re-pair revokes prior pipes and rejects pending operations. Secrets are compared in constant time; remove insecure legacy migration on both sides.
2. Delivery: validate UUID, size/chunk bounds, contiguous chunks, length and SHA-256 before completion. Host file push waits for a matching COMPLETED ACK. Phone marks outbound items delivered only after a successful host inbox ACK. Failed/lost ACKs leave retryable QUEUED items. Missing attachments cannot be committed. Same-name attachments get separate storage paths. No recursive filesystem cleanup.
3. GUI: missing confirmation UI denies execution. Persistent emergency stop, touch interruption, serialized actions, observation ID/window consistency, bounded confirmation and cancellation. A stopped session requires an explicit on-phone resume action; a remote request cannot clear it. Returning from a cancelled confirmation cannot inject a gesture.
4. Approvals: register the waiter before dispatch; use one bounded timeout across MCP/CLI/daemon, persist/validate expiry in the phone card, render supplied options and do not mark the decision delivered until the host acknowledges it. Unknown/expired approval callbacks are errors.
5. Local surfaces and data: reject cross-origin non-JSON mutations, restrict file actions to registered attachments, maintain inbox-derived snapshots after updates. Directory allowlist applies consistently to content URIs and file paths. Document actual BLE GATT/WS relay and remaining verification boundaries.

## Shared interfaces

- Existing JSON-RPC and frame types remain. `StreamAckResumeHeader` COMPLETED means bytes/hash checked and receiver committed; ERROR has errorMessage. Host binds ACKs to the pipe used for the stream.
- `gui.observe` adds `observationId`; `gui.act` supplies it for node actions and `deadlineMs` for all actions. Host CLI/MCP expose the ID, and GUI actions default to a bounded 60-second request. Cancellation never grants permission.
- `notify.requestApproval` includes `expiresAtMs`; `approval.respond` must match a live approval. Options index 0 is approval; other supplied options are refusals with exact selectedOption preserved.

## Validation and boundaries

Convert isolated review findings into regression tests; use simulated sockets only, disable discovery in test harness, preserve fixtures. Run Bun tests/typecheck, Swift typecheck, Android Kotlin compile and available JVM tests. No deployment, global installation, real phone action or publishing is part of this batch.

Secure pairing/data encryption, full BLE backpressure/MTU repair, resumable transfers across process restarts and real WAN deployment remain separate work: this batch must state these limitations accurately and must not claim end-to-end encryption or completed real-device acceptance.

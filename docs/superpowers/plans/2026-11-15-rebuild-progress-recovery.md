# Rebuild progress recovery

The user reported a historical rebuild stuck at zero. Safe on-device diagnostics showed ordinary persona reflection retrying with an exponential backoff while the rebuild remained untouched. A real-device candidate then exposed independent ordinary-extraction validation/timeouts too, so the final policy prioritizes explicitly requested rebuilding over both maintenance phases.

## Change

- Resume idle pending rebuilds without inheriting an old unique-work backoff; append when work is already running.
- Recheck pending rebuilding before/after ordinary maintenance and on failures, preserving the original jobs and deferred persona review.
- Use at most64 historical messages per request and a dedicated hierarchy-only delta prompt. Merge changed roots and children; empty updates retain prior data. Keep30 active summaries in the app and retain older detail nodes for publication/search.
- Persist/display connection, target range, response reception, validation, publication and retry/error phases. Do not log conversations or model output text.
- Preserve the detailed persona validation reason instead of masking every failure as stale.

## Verification

- Core63 tests and C ABI durable write/read passed; Java session/backup/connection checks passed.
- Regression tests cover blocked old work, a request arriving during extraction/reflection failure, small first batches, in-flight status, retained deltas/empty updates and more than30 roots with preserved details.
- Signed Release startup/model inference and ONNX JNI name checks passed in the disposable emulator.
- Existing phone UID, registry, original SQLite rows and credential presence were checked against an on-device baseline without exporting message text.
- On the final candidate, the original phone's cursor advanced beyond zero and the next historical range was received/saved. Processing remains in the background; full history completion and semantic correctness are separate checks.
- One pre-existing long-press UI scenario was intermittent under concurrent build load; it passed in isolation and the final complete suite passed58/58 before publication.

## Finish

- [ ] Final full UI suite, source commit/push, matching version tag and Release asset.
- [ ] Re-download APK and compare size/SHA256; verify tag target and clean workspace.

- Final core63, Java and complete UI58 checks passed. Signed APK startup/inference and safe native hierarchy/publication probes passed. The final APK is installed on the original phone; metadata-only logs prove multiple historical batches saved and continued. No real conversation/model output text was exported.

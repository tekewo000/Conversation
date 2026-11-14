# Hierarchical memory implementation plan

> Implement directly in this session using test-driven-development and verification-before-completion. The user approved the research-based design and authorized implementation through publication.

**Goal:** Keep 30 large experiences with persistent day/scene/event children; automatically organize pending conversation without pause/resume controls.

**Architecture:** Keep compact root summaries in continuity. Store each hierarchy node as a searchable event with an immutable parent link and its original structured fields. Children in extraction results are incremental upserts; omitted children survive. Reuse SQLite/USearch, source validation, archive transactions and WorkManager.

**Tech stack:** Odin/SQLite, Java/Jetpack Compose, Android WorkManager, existing native JNI and ONNX Runtime.

**Spec:** User-approved proposal in this conversation: 30 root experiences, optional intermediate levels, summaries plus source messages, search every level, retain evicted experiences.

## Constraints and review focus

- Preserve existing flat episodes, original messages, persona and relationship state.
- At most 30 roots, four meaningful levels and 128 changed nodes per extraction; bounded transport, no lifetime child-count cap.
- Reject duplicate node keys, reparenting, cross-scope children, invented evidence and oversized/deep trees atomically.
- Search leaves directly and include ancestor context and evidence; do not send whole trees on every reply.
- Back up/replay hierarchy links and export them with the existing snapshot; refuse old readers using schema 6.
- Remove pause/resume/retry UI; ignore the legacy paused preference. Idle work must not call the model; failures retain pending work and retry.
- Release requires source commit/push, matching version tag, signed APK, binding and installed Release startup/inference checks, uploaded asset size/SHA256 verification.

## Tasks

- [x] Add failing core tests for hierarchy persistence, later updates, ancestor retrieval, invalid trees, archive round trips and schema migration.
- [x] Implement node validation/indexing, compact continuity, indexed parent links, retrieval context, paged child reads, snapshot/archive support.
- [x] Add tests and implement extraction instructions/evidence plus hierarchical memory browsing.
- [x] Replace pause/resume UI and persistent pause behavior with pending-only automatic scheduling; test legacy pause and idle behavior.
- [x] Run core/C ABI, Java, native and Android/Compose checks; build signed Release and run required installed-APK probes.
- [ ] Review diff, commit/push, publish matching tag/Release and verify remote APK SHA256 and tag target.

## Verification evidence

- Core: 53 tests plus C ABI durable write/read, all passed.
- Java: connection/session/backup/person/archive/media checks passed; Windows symlink creation check skipped because the host cannot create symlinks.
- Native bridge/USearch checks passed.
- Compose: 46 instrumentation tests passed on the disposable Conversation_UI_Memory_069 emulator, including nested navigation and legacy pause preference.
- Independent review identified provenance-only updates, generic superseding of episode nodes and archive reparenting; fixed with regression tests.
- Leaf embedding input starts with the specific event before ancestor labels to preserve its limited tokenizer window.

- Signed Release v0.7.0: ONNX JNI binding check passed; installed emulator startup/model inference passed; Hierarchy mode passed four-level JNI/retrieval/archive/snapshot checks and synthetic cleanup.
- Upgrade check retained UID/registry/credential-presence baseline (baseline emulator had no histories). Physical devices and live-model extraction quality were not tested.
- Existing Full device probe fails at the combined maximum-size person import. Reproduced the identical failure on the published v0.6.11 APK (SHA256 a065a0812663ef26eb9a87195ea6081da580d473bf13f463356fb33d49ed9221) in a separate disposable emulator; unrelated to hierarchy changes. Hierarchy mode isolates the new feature without hiding the Full result.

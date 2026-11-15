# Historical rebuild and non-reply context notes

The user authorized implementation and release of the previously proposed one-off hierarchy rebuild, and send-button long-press options for scene/meta notes that affect later replies without eliciting a reply.

## Design and ownership

- Core/native: schema7 adds immutable note metadata while keeping user/assistant roles, and immutable completed-rebuild root overrides. Notes enqueue normal memory work; their user role is not evidence of reciprocal spoken interaction. Source messages and all existing records remain immutable.
- Android: long press offers scene/meta choices for the current text draft. Save locally without a reply/model selection/typing bubble; show a distinct note label; later replies receive notes as reference context rather than spoken user turns. Text-only notes preserve draft on failure.
- Rebuild: a per-person `.memory-rebuild.sqlite3` cache stores roots, nodes and source cursor. WorkManager processes bounded original-history batches with the existing selected ChatGPT model. Omitted children remain; malformed output rolls back the stage. New messages extend the boundary. Native publication validates the completed stage under a transaction, adds searchable hierarchy nodes and publishes a root override. Existing history and current memory survive failures. Identical request UUID publication is idempotent. The existing 16MiB archive transaction budget still applies to publication.
- The cache is regenerable working data and is excluded from Git/snapshot exports; final notes/rebuild results are included. Root overrides yield to newer ordinary extraction. No permanent stop/resume controls.

## Plan

- [x] Core tests: note atomicity and non-dialogue evidence; staging validation, boundary changes, publication, retry and archive replay.
- [x] Android tests: long press versus click; no reply for saved notes; annotations in the next reply; staged merge/omitted children/restart/invalid provenance rollback.
- [x] Integrate Core/native APIs with Compose/MainActivity/ChatSession/MemoryWork; keep chat and old memories usable while rebuilding.
- [x] Run Core/C ABI, Java, native and Compose tests; review changes; build/sign new Release and test its startup/inference and feature paths in an emulator.
- [ ] Commit/push matching sources, version tag, publish APK and verify uploaded size/SHA256/tag target.

## Known baseline

The combined maximum-capacity person-import Full device probe fails identically on v0.6.11 and v0.7.0; feature-specific and required startup tests remain separate. Live-model quality and physical hardware need separate verification.

## Verification

- Core63/63, C ABI object/link and separate durable write/read passed. Native bridge/USearch checks passed.
- Java connection/session/archive/person/media checks passed; host symlink creation remains skipped by Windows permissions.
- Compose52/52 passed on emulator-5556. The obsolete no-auth WorkManager-info assertion was replaced by checking durable pending work; note actions wait for the actual enabled send action.
- Review found two cache-recovery defects. Tests first reproduced them, then passed after completed-request publication is checked before boundary extension, status becomes existing-only, and stage initialization becomes atomic with invalid-cache recovery.
- Signed Release0.8.0: installed upgrade check, ONNX JNI binding names, model inference/startup, four-level hierarchy, no-reply annotations, Java-created stage/native publication, idempotent retry, preserved jobs and snapshot tables all passed on emulator-5554. Upgrade baseline contained no histories.
- Physical hardware and actual ChatGPT extraction/response quality remain untested. Final publication still respects the16MiB archive transaction limit.

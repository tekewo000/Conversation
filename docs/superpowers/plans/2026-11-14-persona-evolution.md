# Persona Evolution Implementation Plan

> **For agentic workers:** Use test-first implementation and independent final review. Steps use checkbox syntax.

**Goal:** Persist evidence-backed evolving persona fields and use them in every subsequent conversation.

**Architecture:** Add one immutable revision table and two core functions. Reuse SQLite transactions, original-message evidence, model transport, archive replay and snapshot export. Derive current field values from accepted revisions.

**Tech Stack:** Odin/SQLite, Java 8 Android, C++ JNI; no added dependencies.

**Spec:** `docs/superpowers/specs/2026-11-14-persona-evolution-design.md`

## Constraints and rulings

- Work in the existing checkout: it contains current required uncommitted work. Preserve unrelated diffs and do not create commits or a second checkout.
- Implementation was explicitly authorized after the in-chat proposal; proceed through implementation and checks without another approval gate.
- Fields: `preferences`, `relationship`, `outlook`, `speaking_style`, `disposition`; max five patches, 1024-byte text, 512-byte reason, 32 unique source IDs, 32 KiB payload.
- `conversation_pending_persona()`: `{}` or `{through_id,after_id,person,persona,messages,memories,policy}`.
- `conversation_apply_persona(through_id, payload)`: `{changes:[{field,text,reason,source_ids}]}`; validate and append atomically.
- Context `persona`: `{revision,through_id,fields:[{field,text,reason,source_ids,revision,updated_at}]}`.

## Review focus

- An unrelated topic must retain current persona without retrieving its episode.
- AI-only claims, duplicate or nonexistent evidence must not update durable traits.
- A rejected reflection must not advance the checkpoint or partially apply fields.
- Old SQLite histories/archives must retain data; foreign-person archives remain rejected.
- New revision records must survive full archive replay and ZIP export.

## Tasks

- [x] Core: add failing behavioral tests, implement revision schema, bounded evidence input, field gates, checkpoint and context integration; run strict Odin tests.
- [x] Android: add failing transport tests, add Store contract/current persona/reflection prompt and retry/cancellation handling; run JVM suites.
- [x] Bridge: expose C/JNI methods and person-bound synchronized Store forwarding; compile native and Android callers.
- [x] Archive: add revision descriptor, replay validation, snapshot metadata/JSONL and restoration/export tests.
- [x] Verify strict core/C ABI, JVM, native and Android ARM64/Java compilation; perform independent review and fix important findings.
- [ ] Coordinated UI worker builds/signs the combined Release APK; installation waits for their dialog checks.

## Execution record

- Initial checkout already modified README, person registry/activity and native UI files. Three workers own Android session, bridge, and archives; parent owns core persona logic and tests. The export function is exclusively owned by the archive worker.
- RED: missing persona in every-turn context, missing reflection when the memory queue is empty, missing archive replay/export, and version 2 remaining after upgrade were observed before the corresponding implementations.
- Full-suite retry exposed nondeterministic object key order from Odin JSON marshaling. Compare JSON trees structurally for replay of the same checkpoint; an explicitly reordered payload is covered.
- Final verification: `scripts/test-core.ps1` passed 28 tests plus separate C ABI write/read processes. `scripts/test-connection.ps1` and `scripts/test-native.ps1` passed. The existing symlink test was skipped because creation is unavailable. ARM64 optimized Core and all Android Java sources compiled; only existing deprecation warnings remain.
- Independent review: no remaining Important/Critical findings. Current persona is derived by scanning revision history; materialization is deferred until device/long-history measurement warrants it.
- Full APK packaging/installation is coordinated with the concurrent UI-dialog worker. No competing build/install, remote write, paid inference, or Git commit was performed here. Longitudinal conversation behavior and device cost are unverified.

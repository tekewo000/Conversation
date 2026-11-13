# Compose UI implementation plan

**Goal:** Replace all app UI with Jetpack Compose and publish a signed 0.4.0 APK.
**Architecture:** Java coordinator and Odin storage stay; Kotlin Compose renders all state. Gradle builds Android code, existing scripts compile and sign native artifacts.
**Spec:** ../specs/2026-11-14-compose-ui-design.md

## Tasks

- [x] Native bridge: replace ImGui renderer with bounded message snapshots, retaining persistence JNI and device-probe signatures. Update native regression coverage for pagination, no-person state, large text and rollback.
- [x] Compose build: pin Gradle/AGP/Kotlin/Compose, compile existing Java plus Kotlin, package the existing native engine, retain signing and APK verification.
- [x] Compose UI: replace all View builders and dialogs; preserve every menu action, busy guard, settings preference, per-person draft and secure token form. Add UI tests for unselected state, busy typing, menus, streaming and input state.
- [x] Verify: run existing engine/connection/signing suites, assemble release and instrumentation tests, inspect APK and diff, perform independent review and resolve findings.
- [ ] Publish: commit source, push origin, tag v0.4.0, create GitHub release with signed APK and transparent validation notes, verify uploaded checksum.

## Review focus

Existing data/signing compatibility; retry without duplicate user messages; typing during streaming; browsing older pages while new replies arrive; token state and window security; startup errors without blank screen; font scaling and keyboard/system-bar insets.

Verification boundary: UI8/8 and desktop suites passed. The combined maximum-size Android import probe fails identically in0.3.3; the exact parsed payload imports2048 memories through unchanged Windows core. See docs/validation.md for the recorded limitation.

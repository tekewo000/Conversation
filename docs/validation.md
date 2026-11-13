# Validation record

## 2026-11-13

- Toolchain: Odin dev-2026-11a (`9a69916`), Android NDK r30, Android API 36,
  build-tools 36.0.0, JDK 21.0.10. Android runtime floor API 29.
- First failing core stub and later malformed-JSON case exposed missing behavior;
  all subsequent core tests passed. A new 65,537-byte input regression failed
  before the 64 KiB limit was implemented, then passed.
- `scripts/test-core.ps1`: strict/vetted Odin test plus native C write and read
  processes passed. This verifies actual standalone C startup, not just an Odin
  test-runner context.
- APK built with real Odin ARM64 object linked into C++ JNI library. Package,
  ABI, required GLES version and exported C/JNI symbols inspected.
- APK signature verification passed. Native load segments and packaged native
  library alignment both checked for 16 KiB pages.
- Native bridge compiled with NDK warnings enabled; CPU-only font/gesture/frame
  checks covered Japanese and supplementary emoji glyphs, release processing
  across on-demand frames, scroll gestures, density reset and context recreation.
- Independent reviews found oversized draft state and transcript clipboard Binder
  crash paths. Drafts now use preferences instead of Activity Bundles; clipboard
  failures are caught with an explanation. No native device reproduction was
  possible before the user connects their phone.
- A storage review reproduced ignored-allocation data loss and permissive JSON
  token handling. Precommit capacity reservation and checked allocations now
  prevent invisible committed records; strict token prevalidation rejects
  overflowing numbers, raw NUL, lone surrogates and ignored empty keys before
  loading. Regression tests cover these cases and preserve the original bytes.
- An allocation-failure sweep exercises the first 64 allocation points for
  open/send/pin (192 injected cases). Odin's JSON parser retains six bytes on
  one injected OOM path; this minor upstream allocation leak is deferred. The
  call fails and the original history remains intact.

## Material limits

- This is the approved first input/storage milestone, not the complete AI app.
  Real sign-in, AI responses, automatic memory extraction/retrieval and GitHub
  history sync remain subsequent milestones.
- The initial handoff had no connected phone. The subsequent Pixel 10 Pro check
  below verifies startup/display; physical IME, insets, TalkBack and measured
  idle power still need further phone checks.
- Corrupt history is deliberately preserved and writing disabled. Directory fsync
  recovery for sudden power loss is not implemented.
- Draft preference saving occurs at backgrounding; a forced foreground process
  kill can lose text not yet sent. Sent messages use the durable Odin snapshot.

## Final handoff artifact

- `dist/Conversation-0.1.0-arm64-debug.apk`
- SHA-256 (after phone storage fix): `0800b0879bf3490fb47cc2837f21c8a12290fec38ca5ea19ec206b0cc2cff0ae`
- Final core suite: seven tests passed; the injected OOM parser leak warning above
  remains explicit. Standalone C ABI write/read and native CPU UI checks passed.
- Final APK build, APK signature verification and 16 KiB zip alignment passed.
  Java 8 target and API-29 compatibility calls produce expected deprecation
  warnings when building with JDK 21 / Android API 36.

## First phone check and storage fix

- Pixel 10 Pro connected and authorized over USB. First installation succeeded
  but exposed an existing-directory initialization error in the actual app.
- Odin Linux/Android mkdir_all returns Exist for an existing directory (unlike
  the Windows implementation) and traverses ancestors with read access, which
  Android's app sandbox can deny. Java getFilesDir already supplies an existing
  app-private directory.
- Opening a notebook now stats the final directory directly, creates it only
  when absent, and rejects regular files. Existing history is preserved.
- `scripts/test-device.ps1` failed on the original app's initialization error,
  then passed after rebuild and update installation. The process remains alive;
  the actual Japanese Dear ImGui screen was inspected through a device capture.
- Debug APK now sets debuggable explicitly to permit app-scoped adb diagnostics.
  No app data was cleared or app uninstalled. Manual Japanese IME, actual message
  saving, TalkBack and battery measurements remain to be checked.

## Dark theme and automatic-memory direction

- ImGui, Activity, editor, dialogs and system bars use a dark palette. A rebuilt
  APK was installed on Pixel 10 Pro and the actual display was inspected.
- Native CPU UI/font/gesture tests and APK build tests passed; device startup
  smoke check passed. Existing local conversation and fixed records survived
  the update. The standard clipboard dialog follows the dark Android theme.
- Manual memory buttons no longer interrupt the conversation view. Explicit
  pinning remains available as an optional action in the history menu.
- Automatic semantic memory extraction is specified separately and remains
  unimplemented until AI integration; the UI explicitly distinguishes this
  from the existing automatic transcript persistence.

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
- No phone or emulator was connected during verification. Native GPU output,
  physical IME, insets, TalkBack and measured idle power need phone checks.
- Corrupt history is deliberately preserved and writing disabled. Directory fsync
  recovery for sudden power loss is not implemented.
- Draft preference saving occurs at backgrounding; a forced foreground process
  kill can lose text not yet sent. Sent messages use the durable Odin snapshot.

## Final handoff artifact

- `dist/Conversation-0.1.0-arm64-debug.apk`
- SHA-256: `2aae9ff38712c74461922166479bb03578796cb8d9a4e748a7bbd2d1e4074990`
- Final core suite: six tests passed; the injected OOM parser leak warning above
  remains explicit. Standalone C ABI write/read and native CPU UI checks passed.
- Final APK build, APK signature verification and 16 KiB zip alignment passed.
  Java 8 target and API-29 compatibility calls produce expected deprecation
  warnings when building with JDK 21 / Android API 36.

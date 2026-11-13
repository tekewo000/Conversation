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

## Chat-first interface

- User requested that only conversation content be visible as text on the main
  screen, with almost all available space dedicated to chat and other actions
  grouped in one menu.
- Removed header/title/status/role labels, tab bar, empty-state text and large
  bordered panel. Messages now appear as right-aligned user bubbles. No AI
  messages are invented while the connection is not implemented.
- The native composer has a blank rounded editor plus authored menu/send icons
  with 48dp targets and accessibility labels. Menu opens conversation, list/copy,
  memory, optional pinning and connection state. Errors remain contextual dialogs.
- A native geometry regression failed with the old header/tab layout and passed
  after the transcript occupied the full GL surface. Font, scroll and context
  recreation checks also passed; Odin tests ran in the APK build.
- Installed the rebuilt APK on Pixel 10 Pro and inspected the actual chat and
  menu/keyboard states. Startup smoke check passed. Existing messages and pins
  were retained; no app storage was cleared.
- Android briefly showed its package-update activity immediately after the
  incremental install; a subsequent launch/startup check completed successfully.

## Menu sheet and typography settings

- Replaced the plain list dialog with a dark, rounded bottom sheet using
  consistent outline icons, touch-sized rows and a close control. The chat
  remains free of permanent labels and settings.
- Added Display Settings: text size16–28sp (default20, formerly17), message
  gap8–24dp (default12), live preview and reset. Preferences survive process
  restarts. Input, transcript and native chat use the selected text size, with
  Android's system text scaling included in the native font pixel size.
- Native regression first failed for the previous17sp default, then passed for
  default20, custom font72px/gap20, NaN rejection, range clamping and context
  recreation. Existing Japanese/emoji, scroll and density checks passed.
- Real Pixel10Pro test set the slider to22, closed settings, backgrounded and
  stopped the app, restarted it, then verified the settings label restored22.
  Reset verified stored defaults20/12. No conversation records were changed.
- User noticed a black navigation-area strip below the sheet. Navigation color
  alone did not fix a floating dialog's restricted bounds. The sheet now lives
  in a full-window transparent backdrop, with its panel anchored at the bottom
  and navigation insets padded inside the panel. Real-device capture confirmed
  continuous background to the lower edge while retaining the system gesture
  indicator. The display-settings sheet was left open at defaults20/12.
- Final APK build, signature check and device startup check passed. Other
  Android devices and navigation modes were not available for visual testing.

## Remove unbounded utility flows

- Removed whole-history list/copy, manual pin selection and their unbounded JNI
  string/array builders after the user rejected these operations for long-lived
  conversations. Legacy pinned data remains intact in the stored schema.
- Kept an accessibility-only native text view bounded to the most recent20
  messages and16 KiB, with UTF-8-safe truncation. Native tests cover the count
  bound, byte bound and empty case. This does not make the underlying storage
  or full chat layout scalable; those are still explicit follow-up design work.
- Native checks, APK build and connected-phone startup smoke check passed.

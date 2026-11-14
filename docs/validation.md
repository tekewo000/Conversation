# Validation record

Entries below are historical records, in chronological order. Earlier renderer,
font and milestone limitations describe those versions; the Compose migration
records at the end describe 0.4.0 through 0.4.2. Physical-device evidence from older versions
does not validate the new Compose interface.

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

## 2026-11-13–14: SQLite memory and live ChatGPT

- Implemented pinned SQLite3.51.2 with verified upstream archive/source hashes,
  transactional append-only messages, versioned typed memory, source joins,
  durable extraction jobs and FTS5 trigram indexes. Unknown DB versions and
  missing initialized markers on populated databases fail before legacy import.
- Connected Pixel10Pro upgraded without clearing data. A pre-upgrade backup was
  kept locally under ignored build/. All four original messages matched exactly
  after migration; legacy notebook.json remained untouched. SQLite integrity
  check passed. No personal data or connection state entered public Git.
- Desktop core suite:9 tests plus C ABI write/read in separate processes. Cases
  include20k messages and20k memories, source/scope/type rejection, revisions,
  missing-marker failure, read-only write failure, atomic job rollback, replay
  idempotence and segmented exports. Query-plan and statement-step checks guard
  indexed lookup. The no-hit benchmark is a PC test, not phone latency proof.
- Native CPU suite checks80-row bound with20k records, user/assistant layout,
  paging anchors, source jumps, streaming without forced scroll, Unicode/fonts,
  typography and GL-context state reconstruction. Real device loads the library.
- Connection suite:44 synthetic OAuth/JWT/SSE/JSON checks, plus conversation
  cancellation/evidence sequencing/long-current-input fixtures and backup ZIP
  Unicode/path traversal/current-snapshot-only checks. No synthetic test token
  was used for real authorization.
- Independent review reproduced and fixed inferred-fact supersession, missing
  migration marker corruption, current-user tail truncation, pre-save model HTTP,
  destroyed-Activity login callbacks and rotated-token network gaps.
- The first live OAuth callback succeeded but the token exchange hit Android
  UnknownHostException at background importance400. Final flow waits for app
  foreground; package-targeted return link and singleTask Activity avoid creating
  a second login owner. Real token exchange and ID-token verification completed.
- Live Responses returned HTTP200 with no Content-Type header. The receiver now
  detects the body and requires response.completed or a completed response object.
  Partial/failed/unknown bodies still fail closed. Regression fixtures cover this.
- Real subscription inference answered the synthetic fictional Luna/blue-flower
  recall. Automatic extraction stored an explicit fact scoped fiction and linked
  it to the actual synthetic user message. A subsequent red-flower update created
  a successor, retained blue as historical, and completed all three pending jobs.
  SQLite integrity passed afterward. This is evidence of the live end-to-end
  path, not a long-horizon companionship-quality score.
- Final0.2.0 APK SHA256: `a015d31b21d3e0e951099ab935626d1d5897e7009ce648835111ea74ce43e800`.
  After the final upgrade and process restart, the encrypted ChatGPT session was
  retained and the connection/model controls remained available. Final real chat
  display showed actual assistant replies on the left and user messages on the
  right. The previous authentication error did not recur in the corrected flow.

### Remaining boundaries

Embedding search, graph reasoning and in-app archive restore are not part of
this minimal implementation. Private GitHub backup was added in0.3.0 below. Short queries use
indexed identity matches and a bounded recent fallback. Long extraction inputs
can be marked partial; original content remains stored and searchable. Dedicated
persona training was not performed. Japanese emotional/relational consistency,
over-personalization, forced power-loss behavior, other phones, real token expiry
rotation and long-running companion quality need further evaluation.

## 2026-11-14: Person import and one repository per person

- Added strict UTF-8 `conversation-person-v1` Markdown import, UUID-isolated
  storage and an atomic person registry. Legacy history remains at its original
  path. Repository canonical names and numeric IDs are unique, immutable bindings.
- Profile instructions are present on every reply. Imported memories carry
  document provenance; no synthetic historical messages are created. New explicit
  conversation can supersede old profile facts without losing the source file.
- SQLite v1 migrates to v2 with profile/document tables and a cursor journal.
  Archive batches are bounded and transaction-complete; replay preserves sources,
  updates and extraction checkpoints. Full snapshots include the new tables.
-19 Odin tests and the cross-process C ABI roundtrip passed. Checks include a real
  v1 schema,20k records,4096-event replay, long legacy pins, source SHA256 vectors,
  failed import rollback and parsed snapshot metadata. Native tests passed with
  safe switch rollback/reset and the existing bounded chat rendering checks.
- All seven JVM suites passed:44 connection checks plus ChatSession, ZIP backup,
  Markdown parser, registry, archive files and mocked GitHub transport. Git checks
  cover private-only targets, foreign owners, repository replacement, HEAD
  conflict, interrupted commits and longer-prefix recovery. An anonymous public
  metadata request was rejected without any GitHub write.
- Independent review corrected Activity-destruction races, abandoned sync locks,
  credentials shared across people, long-pin replay and repeated JSON parsing.
  Startup, switch and import now serialize native/registry ownership; destroyed
  Activities cannot continue chat persistence or commit an imported person.
- Real Android instrumentation found generic Odin hash dispatch asserting in an
  ARM64 object build. Direct SHA256 state replaces that reflected initialization.
  The failure occurred only in a fresh synthetic probe directory; the existing
  person's registry/history were not used by the probe.
- Real instrumentation also caught `mkdir_all` requiring read access to Android's
  restricted `/data` ancestors. Initialization/export now create the target
  directly and recurse only when a parent is missing. Desktop checks cover nested
  Unicode paths, repeated export and preserving a blocking regular file.
- Final Pixel10Pro instrumentation passed actual per-person Keystore encryption,
  ciphertext isolation, separate clears, absent-directory initialization, JNI
  import/source provenance, A/B conversation isolation, failed-switch recovery,
  stable archive cursors and Unicode ZIP export. It used synthetic UUIDs only;
  all probe files/keys and its separate signed test APK were removed afterward.
- The existing phone database upgraded from v1 to v2. All10 messages,3 memories
  and3 source rows matched the pre-upgrade content hashes exactly; integrity and
  foreign-key checks passed. The original history was not assigned a fabricated
  person. Final app cold-start smoke check passed.
- Final0.3.0 APK SHA256:
  `72e150a76a9844f8d2ce7c81515f34c4e924bd2e4cadcc7a126124d79d016d66`.

Actual private-repository writes still require the user's repository and token.
Fixture success does not prove live GitHub permissions or long-term AI quality.
GitHub download/restore UI, multi-device merging and scheduled transfers while
the app is closed are not implemented.

## 2026-11-14: Larger person files (0.3.1)

- Raised all four person limits4x: UTF-8 source2MiB, profile128KiB,
  imported memories2048, each imported memory16KiB. Names/headings and the4KiB
  limit on newly extracted AI memories retain their existing bounds.
- Matched core import, replay, Git file validation and error messages. Import
  payloads permit32MiB, journal transactions16MiB, native deltas20MiB, Git JSON
  encoding40MiB and HTTP requests64MiB. Ordinary batch target stays1MiB.
  Snapshot rows permit16MiB; ZIP metadata permits1MiB for the larger profile.
-21 core tests and C ABI roundtrip passed, including exact/new limit+1 atomic
  rejection, a2MiB heavily escaped source, full archive replay and export.
  JVM suites passed; the new Git regression preserves900 long em-dash memories
  whose native JSON fits but encoded JSON exceeded the previous transfer cap.
- Android instrumentation passed one real Markdown file at all four exact caps
  simultaneously through the installed parser, JNI import, full-profile context,
  source/link journal, Git archive generation and ZIP export. Synthetic probe
  files, credentials and its test APK were removed. Existing native tests passed.
- Before/after hashes verified all original rows in both actual phone databases.
  A user message added during work was retained as an append. No actual person's
  file, conversation, credentials or remote repository was used by the probe.
- Final signed0.3.1 APK SHA256:
  `bb6f52b6e75284ea166addb5e33a189e7fe01ec4005d3ae516cba71d36d1280d`.
  Data-preserving installation and app startup smoke check passed.

Large live GitHub upload is not verified here. Large profiles are sent on every
reply and increase model input usage; aggregate serialized import limits still
reject atomically rather than truncating oversized input.

## 2026-11-14: Optimized signed Release APK (0.3.2)

- Release is the default build/install configuration. Each build uses fresh
  isolated staging, Odin `-o:speed`, CMake Release and D8 release mode. Runtime
  bounds checks remain enabled. Native debug sections are stripped from the APK;
  the unstripped library remains under the ignored build directory.
- The packaged manifest is generated with debuggable=false and version0.3.2,
  code5. Signature, package/version, debug flag, ELF16KiB LOAD alignment and APK
  alignment are checked before copying the output to dist/.
- A new3072-bit RSA release key is kept outside the repository under
  `%LOCALAPPDATA%/Conversation/signing/`. Its random password is protected with
  Windows CurrentUser DPAPI and passed to tools through temporary environment
  variables, cleared afterward. Repeated setup preserves the same key; incomplete
  key/password state fails rather than silently generating a replacement.
- Verified v3 signing lineage from the old development certificate to the release
  certificate. The old certificate retains installed-data capability only;
  shared-UID, permission, rollback and authenticator capabilities are disabled.
  Rotation starts atAPI28, below the app's minimumAPI29.
- Desktop verification passed21 Odin tests plus C ABI roundtrip, native tests,
  seven JVM suites (58 connection checks), and signing-key stability/cleanup.
- Before migration, a signed diagnostic saved only hashes/counts of existing rows,
  registry hash, app UID and credential-presence booleans. The in-place Release
  update succeeded. A diagnostic signed with the new certificate then verified
  unchanged UID, registry, old row prefixes and readable existing credentials.
  The baseline contained1 history,10 messages and3 memories. The diagnostic did
  not export passwords, token values or conversation text.
- Release instrumentation passed the real Keystore/isolation/rollback/Unicode
  checks and the2MiB/128KiB/2048/16KiB combined person fixture through parser,
  optimized native import, Git archive generation and ZIP export. Synthetic
  directories/keys and the temporary diagnostic APK were removed. The installed
  package reports version0.3.2 and no DEBUGGABLE flag; startup smoke check passed.
- Final signed APK: `dist/Conversation-0.3.2-arm64-release.apk`,5,542,525 bytes.
  SHA256: `c7dbff892549349f8e44d0c7cda7ec42c266faddcec86d6e843220db0ddde9d6`.
  This build includes the completed local-person and model/Effort/Fast controls.

Fresh live AI inference and a large live GitHub upload were not sent during this
release check. No app-store or GitHub Release publication was performed. Future
updates must retain the release key/password/lineage; old-key Debug APKs cannot
overwrite this migrated installation, and the non-debuggable app denies run-as.

## 2026-11-14: Jetpack Compose migration (0.4.0)

- Chat, composer, menus, display settings, person management, model/connection
  controls and application dialogs now use Jetpack Compose Material 3. The
  C++ library retains storage JNI only; Dear ImGui, OpenGL rendering and bundled
  Noto font assets are no longer built or packaged. Text uses Android fonts.
- Package identity, per-person SQLite paths, preferences, encrypted credential
  stores and the existing Release signing lineage are retained. The source
  manifest is version 0.4.0, code 7. The native-init font arguments remain in the
  JNI signature for compatibility but are empty; the synthetic device probe no
  longer reads font assets.
- Build versions: Gradle 8.13 (download SHA256 checked), AGP 8.13.2, Kotlin and
  Compose compiler plugin 2.2.21, Compose BOM 2025.10.01, activity-compose 1.11.0.
  API 36 / Build Tools 36.0.0, NDK r30 and the existing Odin compiler remain in
  use. Runtime floor remains Android 10 / API 29, ARM64.
- An initial native-to-Gradle Release build passed packaging, v3 signature,
  package/version/debug-flag and ZIP alignment checks. Both packaged libraries
  (`libconversation.so`, `libandroidx.graphics.path.so`) had 0x4000 ELF LOAD
  alignment. Final builds verify every packaged native library, including
  transitive Compose dependencies. This initial build preceded completion of
  the UI migration and is not the final release artifact.
- `assembleDebugAndroidTest` compiled the Compose test APK. The font-free
  `PersonDeviceProbe` also passed standalone javac compilation against API 36.
- Reproduce desktop suites with `scripts/test-core.ps1`,
  `scripts/test-native.ps1`, `scripts/test-connection.ps1`, and
  `scripts/test-signing.ps1`; build using
  `scripts/build-android.ps1 -Configuration Release`.
- After a native build, `scripts/test-compose.ps1 -CompileOnly` compiles the app
  and Compose instrumentation. `scripts/test-compose.ps1 -Serial emulator-5554`
  executes on a disposable synthetic-data emulator. The runner rejects physical
  devices and refuses to replace an installed package whose signing certificate
  differs from the test Debug APK. It never clears application data or requests
  an uninstall to overcome a signing mismatch. Reports are written under
  `build/compose-tests/reports/androidTests/connected/debug/`.

Compose instrumentation uses synthetic input; it does not prove live ChatGPT
authorization, actual private GitHub writes, long-term memory quality, physical
Japanese IME behavior, TalkBack operation or battery performance. Older physical
phone checks above remain evidence for the older UI only.

### Final 0.4.0 verification

- The final full Release build passed all 28 Odin tests, Gradle compilation,
  v3 signature, package/version, non-debuggable manifest, ZIP alignment and all
  packaged ELF 16KiB LOAD-alignment checks. APK size: 22,643,604 bytes. SHA256:
  `f17069ceec6d93f1c02f3d924da039d4917001be1b554b1c54b8facfa2725cf7`.
  Only Compose baseline profiles remain in assets; no bundled fonts are present.
- Separate desktop runs passed 28 core tests and two C ABI durable roundtrips,
  the bounded native bridge suite, 58 connection checks plus the chat/session,
  person, archive and GitHub JVM suites, and signing-key stability/cleanup.
  One symlink-creation check was skipped with FileSystemException on this host.
- `scripts/test-compose.ps1 -Serial emulator-5554` passed 8 tests, zero failures,
  errors or skips. Coverage includes busy draft editing, disabled unselected
  composer, menu/actions, password masking/recreation, Japanese/emoji/streaming,
  both 80-message paging boundaries and actual MainActivity synthetic-person
  creation, draft/settings recreation and deletion. An earlier emulator system
  crash interrupted a run; a cold boot without data wipe then passed all tests.
- The signed Release installed and started on the emulator, reporting code7,
  version0.4.0 and no DEBUGGABLE flag. Installing local0.3.3 followed by an in-place
  update to0.4.0 also succeeded. The emulator originally had no Conversation
  installation; these checks used only synthetic data, not real user records.
- The Release person probe passed Keystore encryption/isolation/AAD rejection,
  normal JNI import, two-person switching, failed-switch rollback, archive and
  Unicode ZIP checks. The combined exact-limit import (2MiB source,128KiB
  profile,2048 seeds,16KiB seed) FAILED with the generic database-operation error;
  the full probe therefore did not pass. Synthetic fixture cleanup succeeded.
  Local0.3.3 with its original probe failed identically on the same emulator.
  The exact parsed 2,796,298-byte payload imported all2048 memories through the
  unchanged Windows C ABI. The Android-side cause remains undetermined; this is
  a pre-existing limitation, not evidence of a Compose regression. Neither the
  limits nor storage implementation were changed to mask the failed check.
- Physical-device IME/TalkBack/power, live ChatGPT authorization/inference and
  actual private GitHub writes were not tested in this migration.

## 2026-11-14: Restore the previous visual style in Compose (0.4.1)

- The user reported that the Compose migration changed the appearance too much.
  The UI now follows the 0.3.3 styling while retaining Compose: right-aligned
  green #2D4434 user bubbles, left-aligned plum #533643 assistant bubbles,
  content-sized widths and 16dp bubble corners.
- Restored the original Noto Sans JP font under `android/res/font/`, with its
  OFL license under `android/res/raw/`. Conversation font sizes convert the
  native renderer's 1448-unit ascent/descent size to Compose's 1000-unit em
  (`1000 / 1448`), while line height retains the selected conversation size.
  The native bridge still receives empty font arguments; Compose owns text.
- Restored the filled dark 48dp composer, blank input without an outlined
  placeholder, green upward send arrow and original line-icon shapes. Menu
  sheets sit flush at the bottom with 28dp upper corners, row icons and a
  header close control; forms retain centered dialogs. Sliders use thin tracks
  and circular thumbs.
- Source version is 0.4.1, code 8. Compose, JNI, storage, authentication and
  backup contracts are unchanged. The combined exact-limit Android import
  failure recorded for 0.4.0 remains an acknowledged pre-existing limitation.
- The final UI suite passed 9 tests with zero failures, errors or skips,
  including masked form input, draft/settings recreation, paging and the sheet
  header close callback. The final signed Release build verified v3 signing,
  code8/version0.4.1, disabled debugging and all native 16KiB alignment checks.
  This UI-only build reused the existing core coverage; it did not rerun the
  unchanged Odin suite or the known failing maximum-size Android import probe.
- Installed and launched the final APK on the emulator. Compared main chat,
  menu, people and settings against 0.3.3 using identical synthetic messages and
  draft on the same device. Menus, icons, composer, bubble colors and glyph
  sizing follow the old screen. Compose's Japanese line wrapping still differs
  from ImGui on some long paragraphs. Synthetic people and the capture helper
  were removed after capture; no real account or conversation was used.
- Final APK: 25,990,148 bytes, SHA256
  `49f0e0cf1faef300046d501162a439f4c6a6b8bd43972f4f12565f851bab0159`.

Visual restoration does not add new physical-phone IME, TalkBack, battery,
live ChatGPT or private GitHub validation. The older ImGui screenshots and the
new Compose screen can be compared for appearance, but do not establish exact
pixel equality across devices or system font scales.

## 2026-11-14: Reduce Compose redraw work (0.4.2)

- Pure menus without fields or secrets now render as an overlay inside the
  existing Activity window, avoiding a separate Dialog window. Forms and secure
  panels retain native Dialog handling; sensitive panels still set the secure
  window flag. `PanelHost` observes panel state separately from the chat, and
  maximum bubble width is calculated once per list rather than independently
  for every message. The overlay scrim covers the full screen, including the
  status-bar background.
- A prior candidate reduced a native sheet window from 2856px to a 636px
  content-height fixture and passed 11 UI tests, but did not improve menu frame
  p95. That window-cropping approach was rejected and is not the final design.
- The final UI suite passed 15 tests with zero failures, errors or skips.
  Coverage includes hiding background accessibility semantics while an inline
  menu is open, Back/scrim/header dismissal, resuming composer input, initial
  focus on Close, focus containment through eight Tab and eight D-pad moves,
  and the actual secure-window flag on sensitive native dialogs. The tests
  found a touch-mode Close-focus issue; the final build includes its fix.
- Release now enables R8 and resource shrinking with Android's standard
  `proguard-android-optimize.txt`. A package-scoped `-keep,allowoptimization`
  rule preserves application class/member names and their existence for JNI
  and separately signed reflection-based probes, while allowing method-body
  optimization and dependency shrinking. The Noto OFL license is explicitly
  retained as a raw resource. This follows the official
  [optimization](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization),
  [keep-rule](https://developer.android.com/topic/performance/app-optimization/add-keep-rules)
  and [resource-retention](https://developer.android.com/topic/performance/app-optimization/customize-which-resources-to-keep)
  guidance. Mapping inspection confirmed 68 original application class names
  and direct DEX inspection confirmed all 14 JNI method names. Only newly
  generated desugaring lambda classes are excluded from the original-class
  naming check. The packaged Noto font and OFL text match their source hashes;
  launcher and foreground icon resources remain present.
- Final signed Release: source version 0.4.2, code 9, 6,666,973 bytes; SHA256
  `a283f7d9aef71426d5d0fa38b23094de868d9bf55e11b57c1bec6f9f1aa1ca95`.
  This is 74.35% smaller than the 25,990,148-byte 0.4.1 APK. The full native,
  Gradle, external signing and verification pipeline passed v3 signing,
  package/version, non-debuggable status, ZIP alignment and all packaged native
  16KiB alignment checks. No R8 warnings or missing classes were reported.
  This rebuild skipped the unchanged Odin tests; it does not replace the
  earlier core evidence or resolve the known Android import limitation.
- Before-change fresh emulator measurements: median startup 968ms; median
  per-run scroll p95 46ms, with 103/1019 frames classified as janky (10.11%);
  median per-run menu p95 61ms, with 211/637 janky frames (33.12%). PSS after
  the menu workload was 97,578KiB. These are baseline observations only; the
  implementation changes and passing regressions do not establish a measured
  speed or memory improvement without matched after-change measurements.

The earlier combined exact-limit Android import failure remains unchanged.
These UI checks add no physical-device performance, live ChatGPT or actual
private GitHub validation.

Matched final Release measurements used the same emulator and synthetic
2,000-message workload. Menu median trial-p95 improved from61 to36ms and
modern jank counts from211/637 to44/776. PSS sampled after menu work fell
from95.3 to74.1MiB. Startup median was968 to1056ms, so startup improvement was
not established. Scroll median trial-p95 was46 to44ms. Both20-second idle
samples recorded zero process CPU ticks and zero frames. These are emulator
observations, not physical-device FPS or battery results. See
[the comparison and limits](performance/0.4.2.md) and
[the aggregated measurements](performance/0.4.2.json).

## 2026-11-14 — 0.5.0 conversation continuity

- B approach implemented: source-linked threads and episodes are generated in the existing extraction request. Replies receive current state, saved message IDs, and bounded original evidence. Episodes retain explicitly reported emotions and scenes, with real/fiction/shared separation.
- `scripts/test-core.ps1`: all 33 tests passed, followed by the C ABI write/read restart harness. New cases cover retrieval after 10 intervening exchanges, malformed evidence rollback, schema 3 migration, preserved state after failure, immutable archive replay, poisoned archive rollback, snapshot state, and prioritization of an old active-thread source under a crowded evidence budget. The 20k-history query-plan and full-scan checks passed. No allocation warnings appeared in the final run.
- `scripts/test-connection.ps1`: passed OAuth/SSE/connection tests (58), chat/session, backup, person parser/registry, and Git archive/backup tests. New session cases verify reply and extraction receive continuity, old retained evidence reaches extraction, future messages stay out of old extraction jobs, and message-ID/truncation markers reach the reply model. Existing symlink creation coverage was skipped because the host cannot create the test symlink.
- `scripts/test-native.ps1`: passed existing native bridge, person rollback, archive, Unicode, paging and 20k-history cases. No new JNI or C ABI functions were required.
- `scripts/test-signing.ps1`: stable keys, password reload, signing lineage and environment cleanup passed.
- `scripts/build-android.ps1 -Configuration Release`: passed the 33 Core tests, Android ARM64 native build, Gradle Release/R8/resource shrinking, signature, package/version/non-debuggable manifest, ZIP alignment and native 16KiB page checks. Manifest version is 0.5.0, code 10. Existing deprecation notices and the dependency strip notice remain; they did not fail the build.
- `scripts/test-compose.ps1 -CompileOnly`: app and instrumentation APKs compiled. UI tests were not executed.
- Signed APK: `dist/Conversation-0.5.0-arm64-release.apk`, 6,675,165 bytes; SHA256 `4742b51f7f9749f1c2e813d8fbcf73ea30ac5f25a0c5c9316b5807988865cb9a`.
- Review found three issues before publication: old active sources could lose the evidence budget to newer neighbors; extraction dropped retained old sources; and reply turns lacked IDs referenced by the state. These were corrected and covered by regression cases. Primary evidence shares a bounded excerpt budget, and secondary sources/neighbors follow it.
- No Android device was attached in the final `adb devices -l` check. No upgrade installation, device startup, Keystore device probe, or device latency measurement was performed for this version. The previously documented Android maximum-size person-import failure was not addressed or rerun in this change.
- No authenticated live model conversations were performed. Synthetic tests prove persistence, source selection and prompt delivery, not natural conversation quality or emotion interpretation. Original evidence is excerpted (at most 2048 characters; less when sharing a budget). Older episodes beyond the bounded current state rely on the existing long-term memory search. Model-generated summaries can still omit or misinterpret information.
- DB schema 1/2/3 migrates to 4 without deleting source messages. Old cores reject schema 4; downgrading the APK is not a supported rollback. New state revisions are included in both Git transaction records and ZIP snapshots. The Git download/restore UI remains unimplemented.

## 2026-11-14 — 0.5.1 background memory and reply text

- Root cause for next-send blocking: ChatSession.reply called processMemories inline, and MainActivity kept busy set until that work finished. HTTP responses also held the connection's shared operation lock through network reads.
- Replies now finish independently; MainActivity releases sending in the reply worker's final UI callback and queues memory work in a separate coalescing MemoryWorker and ChatSession. Persistence keeps storeGate/native serialization, and all person-mutating entry points retain a memory-running guard. Cancellation flags are separate. HTTP auth/refresh is serialized while response transfers overlap; all-requests and foreground-only pools track cancellation.
- Source review found a logout handover race and completed-text cleanup exposing incomplete fragments. Logout now cancels again inside the auth lock; completed cleanup also drops incomplete metadata/control tails. Foreground-only pool cancellation, stale registration and label-fragment behavior have focused host assertions. Full Android OAuth/network orchestration was not exercised by host tests.
- `scripts/test-connection.ps1`: passed Connection 59 checks and all session, backup, person parser/registry, Git, MemoryWorker, HttpRequestPool and ReplyText suites. Latch-based tests hold extraction blocked while the next reply saves, coalesce 100 pending work requests, preserve memory on reply cancellation, and test internal channels, structured metadata, split labels, the exact reported prefix, ordinary multilingual prose and final malformed tails. The existing symlink case was skipped because symlink creation is unavailable.
- `scripts/test-native.ps1` and `scripts/test-signing.ps1` passed. `scripts/build-android.ps1 -Configuration Release` passed all 33 Core tests, ARM64 compilation, Gradle/R8/lint-vital/resource shrink, signing, manifest/code/non-debuggable and alignment checks. Version is 0.5.1/code 11. Existing dependency stripping/deprecation notices remain.
- `scripts/test-compose.ps1 -CompileOnly`: app and instrumentation APKs compiled. Instrumented UI tests were not run.
- Final signed APK is 6,683,357 bytes; SHA256 `4971907c316666926a69fd2ace33c5def54b96cdda38d4a448667a9042ae5490`. Previous 0.5.0 release certificate is retained.
- IDs/times/truncation flags are reference.history metadata, not utterance prefixes. ReplyText cleanup applies during streaming, before saving, when reading assistant history for model input, and on the reader worker before UI history rendering. Old source messages are not rewritten. No private reply bodies or attachments were added to logs or the repository.
- The reported Chinese prefix's origin is unresolved. It is removed as a known unsolicited prefix, not diagnosed as a font problem, a confirmed provider issue, or a confirmed internal channel. The separate SSE channel test is defensive parsing coverage, not proof that channels caused this screenshot. This conversation's pasted prefix was not reliably visible in chat, so the screenshot and Unicode-equivalent fixture were used.
- A Pixel 10 Pro became available after the build. `scripts/install-android.ps1 -Configuration Release` completed an in-place update without uninstall/data clearing, and `scripts/test-device.ps1` passed startup/no-crash checks. Package inspection confirmed version 0.5.1/code 11. No message was sent for testing. Device send-timing, authenticated parallel HTTP, real token rotation/logout races, Chinese-prefix reproduction and live model quality remain unverified. Background execution is within the running process; persisted jobs survive interruption. Existing device-only maximum-person-import limitations remain outside this change.

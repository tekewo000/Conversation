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

## 2026-11-14 — 0.5.2 assistant current feeling

- Extended existing continuity JSON with assistant_state: current emotion, reason, triggering event, scope and one to eight source message IDs. Text fields are bounded to 768 UTF-8 bytes each within the existing combined 12 KiB state budget. No new network call, database table or dependency was added.
- Reply and extraction prompts carry the current feeling separately from enduring persona. Its original messages participate in bounded retrieval and extraction evidence even after ten intervening exchanges. New conversations can update the state or explicitly clear it with null. Legacy results omitting the field retain the previous state; the merged state is validated before commit.
- RED: Core rejected the new state shape, and the session test failed because feeling instructions were absent. GREEN: `scripts/test-core.ps1` passed all 36 tests plus the two-process C ABI roundtrip. Added coverage includes restart and archive replay, poisoned archive rollback, old-format carry-forward, null clearing, malformed sources/fields and combined byte-budget rejection without advancing the job. No allocation warnings appeared in the final run.
- `scripts/test-connection.ps1` passed all connection/session/backup/person/Git/worker/text suites, including delivery of old feeling evidence to extraction. Its existing symlink case was skipped because creating symlinks is unavailable. `scripts/test-native.ps1` passed.
- Long-term retrieval remains SQLite FTS/structured retrieval; embedding-based search was not added. No authenticated conversation or device installation was performed. Tests validate state delivery and persistence, not the model's emotional interpretation or naturalness. Background extraction can lag the next reply, which receives the previous state plus newer messages.
- `scripts/build-android.ps1 -Configuration Release -SkipTests` passed ARM64 native compilation, Gradle/R8/lint-vital, release signing, manifest version 0.5.2/code 12, non-debuggable and alignment checks. Core tests ran separately as recorded above. Artifact: `dist/Conversation-0.5.2-arm64-release.apk`; SHA256 `9590090275d97c9ebda81d80b6a0ac1da94b181b0dd3f7ccd440edfaa048972b`. Existing dependency stripping/deprecation notices remain.

## 2026-11-14 — 0.5.3 person icons, feeling labels and thirty exchanges

- AI bubbles now have a 48dp accessible avatar control on the left. The newest assistant bubble (or streaming reply) alone displays the current feeling below its avatar; reasons, events and source IDs are not shown. Empty state has no invented mood. Avatar taps and the People menu open the image picker/reset actions; no header was added.
- Picked images are read on a worker with a 16 MiB input cap, decoded at no more than 512 pixels on their longest side, center-cropped and saved as 256-square PNG in the person's private directory using atomic replacement. The registry validates PNG signature, byte limit and storage paths. Picker ownership survives activity state restoration and rejects a changed person. Icons participate in ZIP snapshots and the existing hashed persona.json Git backup, including icon-only changes and removals with no new journal entries.
- Added an O(1) latest-revision feeling getter through the C ABI/JNI; the UI does not call RAG for mood labels. Memory completion and partial failures both refresh the visible feeling. No image loader dependency or additional model call was added.
- Expanded reply history to the previous thirty exchanges plus the current user input (61 saved messages under ordinary alternating conversation). Recent excerpts retain their 2048-character limit with a 768 KiB escaped-JSON budget, sufficient for all 61 even with six-byte control-character escaping. Current user input is replaced with its full text in ChatSession. Extraction jobs, their twelve-message supplemental evidence validation, old-source retrieval and other budgets remain separate.
- RED: registry icon APIs and the feeling getter were missing; Compose lacked avatar state; Git dropped the optional icon. The new long-history test returned two messages instead of 61 before increasing both count and budget. GREEN: `scripts/test-core.ps1` passed all 37 tests and the two-process C ABI roundtrip. Older-source tests now insert more than thirty exchanges so they still test retrieval beyond the recent window.
- `scripts/test-connection.ps1` passed its connection/session/storage/backup/person/Git/worker/text suites, including isolated icons, failed replacement, restart/removal, ZIP inclusion, hashed Git metadata and icon-only sync/removal. The existing symlink-creation test remains skipped on this host. `scripts/test-native.ps1` passed.
- The first emulator run lost its host GPU context (`eglMakeCurrent failed`) and stopped. Restarting the same test AVD without loading its snapshot and with SwiftShader completed all 18 Compose/device tests. Coverage includes icon normalization/rejection, current feeling-only display, recreation and reset, and the incumbent menu/composer/accessibility behaviors. No live model request or physical-device installation was performed.
- RAG engines were researched from upstream sources; neither sqlite-vec nor USearch was installed or benchmarked. Long-term retrieval remains the existing SQLite FTS/structured search.
- Final `scripts/build-android.ps1 -Configuration Release -SkipTests` passed native/Gradle/R8/lint-vital/signing/manifest/non-debuggable/16 KiB alignment checks for 0.5.3/code 13. The APK is 6,695,645 bytes, SHA256 `bf6c60b2edf1fcdd1167763d6b32360592c5218b2885315d57f5a4c2a88c7b7d`. The avatar display test was rerun once to capture the synthetic conversation to `build/avatar-chat.png`; visual inspection confirmed the left-side avatar and feeling-only label. The physical device was not updated, and the RAG candidates' Android performance remains unmeasured.

## 2026-11-14 — on-device USearch RAG / 0.6.0

- Published v0.5.3 from commit `15a8d3235cdf3cab1e3a4cab8e7d40163248aa03` before semantic changes. The public GitHub Release is stable, with its APK size and SHA256 verified against the local 0.5.3 artifact. USearch is developed separately on feature/usearch-rag.
- Added pinned USearch 2.26.2 headers, SentencePiece 0.2.1 and ONNX Runtime Android 1.23.2. Native sources are checked by SHA256 in setup-rag.ps1; required licenses/notices are packaged in assets. Ruri v3 30m int8 ONNX and the original tokenizer are pinned by model revisions, exact sizes and SHA256. An actual emulator download completed and verified both public assets. No retrieval API key or additional remote embedding request is used.
- Foreground reply retrieval embeds the current text locally, searches the person's USearch index, and combines candidate ranks with FTS in Core using RRF. The resulting IDs are resolved into the original SQLite records and evidence. Memory extraction retains its separate context path and evidence restrictions. Stale superseded IDs are filtered by Core even during a background update. Existing per-reply limits and thirty-exchange history remain.
- A separate per-person SQLite vector cache stores only rebuildable vectors, model version and an atomic processed cursor. The original Odin database and immutable archives remain authoritative. Cached vectors rebuild the HNSW graph on restart. Superseded vectors are removed; corrupt caches are discarded and regenerated. Close stops access to a person's cache before deletion, and background source calls check the current person/Activity before entering Core.
- RED: Core semantic/batch exports, native index wrapper, reply retrieval hook and local model classes were absent. GREEN: the new Core retrieval test verifies candidate validation, source-linked selection, bounded embedding batches, no candidate leakage and rejection of superseded candidates. The existing host suites continue to pass.
- `scripts/test-core.ps1`: all 38 tests and the two-process C ABI roundtrip passed. `scripts/test-connection.ps1`: all connection/session/storage/backup/person/Git/worker/text suites passed; the existing symlink test remains skipped on this host. `scripts/test-native.ps1` passed bridge cases and the real USearch fixture with 10,000 vectors, updates, removals, bad-vector rejection and 100 warm host queries. The latter took about 55 ms total on this PC; it is not an Android speed claim.
- `scripts/test-compose.ps1 -Serial emulator-5556`: all 21 tests passed on the Android 16 x86 emulator running the ARM64 libraries. New tests execute real quantized inference and native tokenization, match a Japanese/emoji token fixture, rank a paraphrase with USearch, reuse a restarted cache, regenerate invalid cached vectors, remove superseded vectors, and isolate people. An Activity test seeds 32 real Core memories, verifies plain FTS misses the old target, then confirms the real reply store retrieves that target semantically with its original source ID. Its reply transport is synthetic and sends no ChatGPT request.
- The first cache test used cacheDir and failed after Android reclaimed that disposable directory under storage pressure. It now uses a dedicated filesDir fixture, matching real person storage, and passes. This did not require changing production cache placement.
- Final signed release build passed ARM64 native compilation, Gradle/R8/lint-vital, manifest 0.6.0/code 14, non-debuggable checks, release certificate and 16 KiB alignment for all native libraries. APK: `dist/Conversation-0.6.0-arm64-release.apk`, 14,396,781 bytes, SHA256 `d5b1a47d9aa66baa40b5af5d5489dca6db7355db5ebfbeb6a47b56af64e527d9`.
- Physical-device latency, long-term Japanese recall quality, multilingual recall and authenticated ChatGPT conversation were not tested. Embeddings use at most 256 tokens per query/memory; long-document chunking is not implemented. Model setup/backfill can be pending while ordinary FTS conversation continues. Search cache files and downloaded models are deliberately regenerated instead of included in Git/ZIP archives.

## 2026-11-14 — 0.6.1 Release JNI startup crash

- Actual Pixel 10 Pro crash records for 0.6.0 show SIGABRT from `convertToTensorInfo` / `OrtSession.run`: `JNI DETECTED ERROR IN APPLICATION: java_class == null` in GetMethodID. Release R8 mapping renamed ONNX types including OnnxJavaType, OrtException and OrtLoggingLevel. The previous 21 instrumentation tests exercised Debug rather than the minified Release APK, so they did not establish Release runtime safety.
- Added the upstream-required `-keep class ai.onnxruntime.** { *; }` ([ONNX Runtime Android build guidance](https://onnxruntime.ai/docs/build/android.html)). The rest of the app's R8 optimization remains enabled. The new build-time `test-release-bindings.ps1` rejects missing/renamed JNI types before packaging the final APK; it fails on the original 0.6.0 mapping and passes on 0.6.1.
- Added a separately Release-signed framework instrumentation probe. It loads the installed target's RagModels and LocalEmbedder, performs real 256-dimensional normalized inference, launches MainActivity and, when a person is selected, waits for background semantic indexing to report ready. No production classes are bundled into the probe and no ChatGPT request or conversation message is created. The original 0.6.0 Release APK aborts with the same native signature; 0.6.1 passes on an isolated emulator and on the physical Pixel 10 Pro.
- `scripts/test-person-device.ps1 -Mode Baseline` recorded only hashes/counts, registry digest, UID and credential-presence booleans before the upgrade. Added its missing PersonRegistry compilation dependency after icon backup introduced that dependency. `install-android.ps1 -Configuration Release` then upgraded the phone without uninstalling or clearing data. `test-person-device.ps1 -Mode Verify` confirmed the original SQLite rows, registry, UID and readable credentials were retained. `test-device.ps1` passed with the upgraded app running and no initialization/crash error in its current process log.
- The concurrent icon-detail change is included from commit 3ac9cb5. Its final Compose run passed 22 tests after correcting asynchronous assertions; runtime sources were frozen before the final Release build. Changes display only the saved emotion/reason/event and retain the image-settings action. No private messages or device serials are included in the release files.
- Release build run-3410c8da985147deb0c73a1bc1180881 passed native/Gradle/R8/lint-vital, the new ONNX binding check, signing, manifest 0.6.1/code 15 and native 16 KiB alignment checks. The tested APK is 14,474,605 bytes, SHA256 `1afadb955e93007eed6cf1756c5434ee292f7a8ae17dcf989420cba897da3d63`. Authenticated ChatGPT replies and long-term recall quality were not exercised.

## 2026-11-14 — 0.6.2 Markdown dialogue

- Both roles and the provisional streaming reply use the existing MessageText path with Markdown Renderer 0.38.1. Parsing runs asynchronously and retains the last rendered content during updates. Plain newlines, the Noto font/size settings, bubble colors and SelectionContainer remain; storage and request text are unchanged. Code uses monospace, headings scale from the configured size, and links retain that size. External image loading and math rendering are not included.
- The new regression failed before implementation; the other 22 tests passed. The final `test-compose.ps1 -Serial emulator-5560` passed all 24 tests. New checks cover user bold spans/inline code, assistant headings/list/quote/link/fenced code, streaming updates, tables, ordered lists, strikethrough spans and escaped delimiters. The incumbent Japanese/emoji/newline and activity/history tests passed. Inspected the captured table/list screenshot at the emulator's narrow 320dp width.
- Release build run-a00b22d1063d42cfb5f3a3acbb32b1b6 passed all 38 core tests, native/Gradle/R8/lint-vital, ONNX JNI binding validation, signing, manifest 0.6.2/code 16 and native 16 KiB alignment checks. `test-release-startup.ps1 -Serial emulator-5560` passed real 256-dimensional normalized inference and Activity startup against the installed, signed, minified Release APK.
- APK: `dist/Conversation-0.6.2-arm64-release.apk`, 14,708,160 bytes, SHA256 `3081a43548516fd541838643c587d2b307884dffb12febef0c058ccfb2764890`. No physical-device installation or authenticated ChatGPT conversation was performed for this version. The v0.6.1 tag and asset were preserved.

## 2026-11-14 — 0.6.4 Media attachments and separate paid image generation

- Moved the menu to the top right and added the bottom-left attachment action. Images, MP4/WebM/MOV, PDF and UTF-8 text are copied into per-person content-addressed storage; message text holds relative references. Images and video previews retain their aspect ratios, transparent bitmaps retain alpha, images expand and videos play through the platform player. Draft attachments and explicit paid-image mode survive Activity recreation. ZIP/Git include referenced binaries; removed unsent attachments are excluded. See `media-attachments.md` for size and history resend limits.
- Normal conversation, image recognition, video-frame analysis and memory work keep the existing ChatGPT subscription client. Explicit image generation uses a separate API-key client at `/v1/images/generations`, with only the current text prompt, one image, medium quality, automatic size and PNG. Its default model is gpt-image-2.5-flare and can be changed in app. The key is entered in a masked FLAG_SECURE panel and AES-GCM encrypted in a distinct Android Keystore/noBackupFiles namespace. The legacy ChatGPT alias, AAD and filename remain byte-for-byte compatible. Keys do not enter exports, source or APKs.
- Added a cancellation check after context preparation and carry the paid request generation token from before preprocessing. The regression first reproduced a transport starting after cancellation, then passed with both gates. No automatic paid retries occur; the retry action is explicitly labelled as paid regeneration.
- `test-compose.ps1 -Serial emulator-5566` passed all 35 tests. Coverage includes actual four-colour H.264 frame extraction (including non-keyframes), frame time order and aspect ratios, video playback, image/alpha rendering, attachment-only drafts, current four-PDF inclusion, recovery after oversized history, URI import and recreation, masked key save/delete, encrypted credential isolation, and the incumbent chat/person/memory UI. `test-connection.ps1` passed its Java suites, including 60 connection checks and attachment/Git/ZIP/API request/response/cancellation regressions. All 41 core tests passed with the unchanged native sources in the earlier 0.6.4 build.
- Final signed build run-764b9079f0bd4388aade6feb18240add passed ARM64 native compilation, Gradle/R8/lint-vital, ONNX JNI-name validation, manifest 0.6.4/code 18, signature and 16 KiB alignment checks. `test-release-startup.ps1 -Serial emulator-5566` passed real model inference and Activity startup against this exact minified Release APK. APK: 14,790,136 bytes, SHA256 `f09ab9a6ed036f14aac282005b4019d22fd6f994c7345ddca3518c9c88d3e96f`.
- An earlier candidate before the paid API split passed real-phone Release inference/background indexing/startup, preserved the original UID/registry/readable ChatGPT credentials and all 2,035 conversation rows/746 memories, and correctly read HELLO from a synthetic image through the authenticated subscription client without creating conversation rows. The same account rejected the subscription image-generation tool; the official token-sharing restrictions confirm that it is unsupported, motivating the separate paid client.
- The phone disconnected before the final APK update, so final physical-device installation and authenticated vision re-check were not performed. Actual paid image generation was not invoked: the user chose to enter an existing key in the app, and no key was supplied to the agent. The user requested one combined v0.6.4 release including the immediate-feeling commit caf6f2b; no v0.6.3 release was made.

## 2026-11-14 — Source-backed relationship continuity (working changes)

- Added qualitative trust, openness and their basis to optional `continuity.relationship`, separate from current feeling and enduring persona. Original source messages accompany unrelated replies, later extraction and persona reflection. Reflection uses relationship state at or before its own checkpoint, including up to eight older relationship originals outside its recent-64 window while retaining the 40,000-byte budget. Its validator now checks exact membership in that supplied evidence, including gaps between old and recent messages.
- New or changed relationship state requires a fresh user and assistant source from the extraction job. Unchanged state can retain old sources. Omission retains the previous state; null, malformed or oversized state is rejected. Carry-forward is included in the existing 12 KiB combined state validation. Archive and snapshot formats reuse existing continuity rows; older records remain accepted, while older app versions cannot restore new relationship fields.
- RED: the new Core tests rejected the unsupported relationship field; the Java transport regression showed that old relationship sources were omitted from extraction. GREEN: `scripts/test-core.ps1` passed all 45 tests and both C ABI durable roundtrips. Coverage includes calm mood with unresolved trust, 35 unrelated exchanges, restart, snapshot export, archive replay, later repair-state replacement, invalid/stale evidence, rollback, combined byte limits and persona review boundaries. These use synthetic, supplied state; they do not measure a model's choice to reconcile.
- `scripts/test-connection.ps1` passed all Java suites, including actual serialized reply/extraction/reflection payloads and preservation of the existing request count. The existing symlink test skipped because symlink creation was unavailable. `scripts/test-native.ps1` passed the native bridge and USearch tests. A separate read-only code review found no important defects in the Core and Java changes.
- `scripts/build-android.ps1 -Configuration Debug -SkipTests` passed ARM64/native/Gradle/signature/alignment checks after the separate test runs. Output: `dist/Conversation-0.6.4-arm64-debug.apk`, SHA256 `c0aed2fdd817d5594edb9317a7cce40d92a2172e8cc234691aeef7e8d2686eae`. Build directory: `build/android-debug/run-b4586e82e0f9443485bf2702a9d6b688`. Existing Kotlin visibility, Java deprecation and Gradle deprecation warnings remained. This is a Debug verification artifact, not a new published Release.
- No installation, authenticated inference, paid API call or longitudinal conversation evaluation was performed for these changes. Semantic judgments remain model-dependent; neither source counts nor passing transport tests prove natural reconciliation, resistance to unwarranted affection drift, or gradual personality development. The conversation cases in `automatic-memory.md` remain to be evaluated with real model responses.

## 2026-11-14 — v0.6.5 signed Release and physical-device upgrade

- Bumped the source manifest to 0.6.5/code 19. The first release attempt stopped at a test that assumed `trust` was the first JSON property. The regression now parses the property value, passed with the failing seed, and the final build passed all 45 Core tests. No production behavior was changed to satisfy property ordering. The signing and full Java suites also passed; the existing symlink-creation test remained skipped.
- Final build `build/android-release/run-9067b245cecb471fb1b236ad2bf7d2e3` passed ARM64 compilation, Gradle/R8/lint-vital, `test-release-bindings.ps1`, manifest checks, formal v3 signing with the existing lineage and 16 KiB alignment checks. APK: `dist/Conversation-0.6.5-arm64-release.apk`, 14,794,232 bytes, SHA256 `f8a249c3ec83f8238fcab7095f359c7d21cde5aaa7653875d1961cdd5deeadff`.
- Before installation, `test-person-device.ps1 -Serial 56211FDCH0045H -Configuration Release -Mode Baseline` recorded only existing-row digests/counts, registry digest, UID and readable credential-presence booleans. `install-android.ps1 -Serial 56211FDCH0045H -Configuration Release` updated the Pixel 10 Pro from 0.6.4/code18 using `adb install -r`. `-Mode Verify` confirmed the covered old SQLite rows, registry, UID and credential readability were retained: one history, 2,051 messages and 746 memories. This digest harness covers its listed tables, not every cache, attachment or new continuity table.
- `test-release-startup.ps1 -Serial 56211FDCH0045H` passed against the installed signed, minified APK: real local embedding inference, finite 256-dimensional normalized vector, MainActivity startup and background semantic-search readiness. The installed manifest reports 0.6.5/code19. This is local model inference, not an authenticated ChatGPT conversation-quality evaluation.
- Longitudinal conflict/repair/openness and personality-growth quality remain unverified. New relationship archive fields remain unsupported by older application versions. Existing Kotlin visibility, Java deprecation and Gradle warnings were not changed by this release.

## 2026-11-14 — v0.6.7 Original image download

- Added Save buttons to stored image cards and expanded images. The app verifies the existing attachment fingerprint and copies its original bytes to MediaStore Downloads under `Download/Conversation`; it does not re-encode the preview bitmap, regenerate an image, or make an API request. No storage permission was added. Drafts and videos do not receive this action. Incomplete downloads use IS_PENDING and are deleted on failure without deleting the source attachment.
- The missing-button regression failed before implementation. The focused attachment suite passed 10 tests after the save notification was explicitly dispatched on the main thread. After integrating the 0.6.6 generated-image history fix (523e7e1), the full Android suite passed all 39 tests. New tests exercise the real MediaStore provider, check saved PNG bytes/MIME/path/IS_PENDING, preserve the source file, click Save, and locate the action in the expanded view. Only the test-created download URIs are removed during cleanup.
- The Java baseline suites passed and the build's 45 Core tests passed. The first native build hit the Windows long-path limit in the managed worktree; the same source built successfully through a temporary short drive mapping. Final signed build `run-d643a728e39a4679a66ed09e586fda9d` passed Gradle/R8/lint-vital, ONNX JNI binding names, manifest 0.6.7/code21, existing release signing lineage and 16 KiB native alignment checks. APK: 14,802,424 bytes, SHA256 `21cd415bb586410882f19c284a58d43b3482de74f58bb56ed2edbfe4da4e56e8`.
- The physical Pixel 10 Pro upgraded without uninstalling. The signed Release passed real ONNX inference/background indexing/Activity startup, retained the covered original 2,080 conversation rows/746 memories, UID/registry/readable credential presence, and its installed APK SHA256 matched the tested artifact. A real personal image was not exported during diagnostics; the end-to-end save operation was exercised with synthetic images on the disposable Android emulator. No paid image generation was invoked.

## 2026-11-14 — v0.6.8 Reply continuity and typing indicator

- A reply now shows the person icon and three pulsing dots immediately after sending. The pending row retains its LazyColumn key when the saved reply arrives; the activity keeps partial text visible until that snapshot is applied and only then enables another send. Emotion updates reuse the separate message-body composition. Cancellation removes the pending row.
- Removed the per-text-update scroll-to-bottom effect. A regression first failed because the reader's scroll position changed from 500 to 0 on a streaming update, then passed after removing that trigger. The existing reverse layout keeps a reader at the bottom following newly received text.
- `test-compose.ps1 -Serial emulator-5580` passed all 43 tests. New coverage checks typing before any reply text, retained icon/body semantics identities across streaming/save/emotion updates, cancellation, reader scroll position, and a blocked snapshot reader with a concurrent history-page change. No paid API call was made.
- Release build `run-ef18544fccea4c0682cd074c88ddbd25` passed all 45 Core tests, ARM64 compilation, Gradle/R8/lint-vital, `test-release-bindings.ps1`, manifest 0.6.8/code22, existing signing lineage and 16 KiB alignment checks. APK: `dist/Conversation-0.6.8-arm64-release.apk`, 14,810,616 bytes, SHA256 `0015d4540471483163875ba21988d1cd22d883e3e9dec70b65db8df8d8f85790`.
- `test-release-startup.ps1 -Serial emulator-5582` passed actual embedding inference and Activity startup against the installed signed, minified Release. Physical-device frame timing, perceived smoothness and an authenticated live ChatGPT exchange remain unverified for this version.

## 2026-11-14 — v0.6.9 Scene memory and icon details

- Episodes now group a continuing place/time/activity and split on an explicit location change, new day/time context or new activity. The extraction prompt keeps stable scene keys, separates recollection from the current scene, retains the latest 30 scenes, and preserves real/fiction/shared distinctions. Common reply instructions suppress commentary about roleplay, prompts and internal memory mechanics.
- Validated episodes are also saved as source-linked event revisions in the existing memories table. Unchanged summaries do not create duplicates; same-key/scope changes supersede their previous searchable version. Evicted scenes remain available to FTS and semantic indexing. The state budget is 64 KiB, extraction accepts 128 KiB, and reply feelings retain their independent 12 KiB limit. Existing archives and tables are reused; old, already-evicted episode revisions are not bulk reprocessed.
- `scripts/test-core.ps1` passed 47 tests plus the C ABI durable roundtrip. New cases cover scene revision/deduplication, place/time/activity/scope separation, retrieval after eviction and restart, semantic indexing, 30 detailed scenes across transport and archive replay, and atomic rejection of scene 31. Carry-forward feeling/relationship budget tests use the expanded state capacity. `scripts/test-connection.ps1` passed, including the larger bounded memory payload; existing host symlink-creation coverage remains skipped.
- The Compose 43-case run passed 42 cases, with the new memory scenario failing on test-only substring matching and then icon ambiguity after adding history. After correcting those assertions/selectors, all eight MainActivity tests passed on the isolated `Conversation_UI_Memory_069` emulator. No app code changed between those runs. Covered short-history filtering to 61 messages despite older retained evidence, all 30 scenes in newest-first order, empty episodes, state retention/recreation, back navigation and icon reset. Inspected synthetic screenshots of the memory overview and scene list; no personal-device screenshots or paid ChatGPT requests were used.
- Release build `run-f8f3f38583d447ea8ff3f6ab248822f6` passed its 47 Core tests, ARM64 compilation, Gradle/R8/lint-vital, ONNX JNI binding verification, manifest 0.6.9/code23, existing signing lineage and 16 KiB native alignment checks. APK: `dist/Conversation-0.6.9-arm64-release.apk`, 14,818,808 bytes, SHA256 `5a6ac37eec3c0edb7291d3f067cdef3d340d90305233a1edd20434ab3e48c746`.
- The connected Pixel 10 Pro upgraded without uninstalling. `test-person-device.ps1 -Configuration Release -Mode Baseline/Verify` confirmed original row digests for 2,274 messages/746 memories, app UID, registry and readable credential presence. `test-release-startup.ps1` passed actual ONNX embedding inference and Activity startup on the installed signed Release. The pulled installed APK matched the tested artifact's SHA256 and version.
- Long-running real conversation quality, correct model-inferred scene boundaries and absence of meta commentary remain unverified. Memory details show the saved snapshot and bounded reply-context excerpts; reading them does not make an AI request. Backups exceeding the former six-scene/12 KiB limits require a supporting app version to restore.

## 2026-11-14 — v0.6.10 Memory backlog catch-up

- A read-only, same-signature device probe found 411 pending jobs, with 2,325 saved messages but extraction only through message 1,494. The last ten revisions were advancing while retaining six episodes; the native limit was already 30. The apparent stuck count was an old conversation state, not a remaining six-item validation limit. Added `test-person-device.ps1 -Mode InspectMemory` to report only counts, boundaries, revision times and sizes; it does not print conversation text or credentials.
- Pending extraction now joins the contiguous oldest pending jobs up to 16 original messages and the existing source byte budget. The app overwrites any model-supplied batch boundary with the store's boundary. On success a consolidation job, memories, continuity and original job completions are committed/journaled together; failure rolls everything back. Existing job ranges and originals are retained. State/archive validation resolves the earliest job boundary for a shared end position.
- A three-batch pass reports remaining work and the Activity schedules another pass, rather than waiting for a new reply. The memory sheet reports processed/total message counts and remaining messages, offers continuation, and can pause before the next AI request without discarding an in-flight successful result. Network/storage failures stop continuation for explicit retry.
- `test-core.ps1` passed 48 tests and the C ABI durable roundtrip. The new regression initially failed on the missing batch metadata; after implementation it covers 20 original jobs, rejection of skipped/oversized/future-source batches, atomic rollback, idempotent consolidation, old/new source IDs, progress and archive replay. An extended focused run also passed continuation across newly appended jobs and consolidation-ID gaps. `test-connection.ps1` passed, including store-owned batch metadata and the remaining-work result. Host symlink creation remains unavailable/skipped.
- `test-compose.ps1 -Serial emulator-5584` passed all 43 tests. The Activity scenario additionally checks processed/total counts, 64 unprocessed messages, pause reservation and state, alongside the existing short-history and 30-scene tests. No ChatGPT request was made by those tests.
- Release `run-f9a2347bd9cf434485e2526a637aadae` passed 48 Core tests, ARM64 compilation, Gradle/R8/lint-vital, ONNX JNI binding names, manifest 0.6.10/code24, signing lineage and 16 KiB library alignment. APK: 14,818,808 bytes, SHA256 `221c8926bbb3528b26d506daefd2a31f4e5f0de604aa0836d4068823894c349d`.
- The connected Pixel 10 Pro upgraded without uninstalling. The Release baseline/verification retained original digests for 2,327 messages and 761 memories, UID, registry and readable credential presence. The extended signed Release startup probe passed ONNX inference/Activity startup, read a real 16-message contiguous pending batch and progress 1,494/2,327 without calling ChatGPT. The installed APK SHA256 matches the build artifact.
- The existing backlog was not bulk drained using the live account during diagnostics. End-to-end model extraction quality and wall-clock catch-up time remain unverified; background processing uses the subscription allowance and can be paused. New consolidation archives require 0.6.10+ for restoration. There is no DB-table/schema version change.

## 2026-11-14 — v0.6.11 Automatic persistent memory and whole-backlog extraction

- Replaced the Activity-owned MemoryWorker with WorkManager 2.12.0 and an Application-context MemoryStore. Startup/reply completion enqueue a durable unique work chain with a network constraint and exponential retry. Pausing is persisted; logout cancels the chain. Native storage operations retain the common gate and recheck the selected person before every access. UI destruction only cancels the reply connection, not the memory worker's connection.
- A batch now walks contiguous pending jobs until its serialized source budget reaches 256 KiB, with a 4,096-message safety bound, rather than stopping at 16 messages. The sequential cursor stops at the first out-of-budget job. Extraction may return up to 32 memories while retaining the existing 128 KiB result/state/source checks. Windows Core tests (48) and the C ABI roundtrip passed; the extended escaped-input test separately rejected an oversized combined payload without marking originals processed.
- The memory screens observe WorkManager state/progress and reread saved snapshots while open. The full 45-case Compose run passed after changing an existing image-key deletion test to wait for the asynchronous completion message instead of reading during deletion. All eleven final Activity cases also passed, including the added no-reopen live update, a real SQLite memory commit after Activity destruction, and fresh credential reads by the UI/background connections. No AI request is used by those synthetic tests.
- UI and background authentication instances share their refresh lock and reload the saved credentials before authenticated operations. The new larger live request hit the former 120-second total deadline without committing data. Added a bounded background-request timeout: extraction may wait 300 seconds; reply and reflection remain 120 seconds. Worker requests remain inside WorkManager's execution budget. A final build was made after those edits; earlier local test APKs were not published.
- Final Release `run-056ab4ffce07429cbcddc3e7fb4b43c0` passed ARM64 compilation, Gradle/R8/lint-vital, ONNX JNI name verification, manifest 0.6.11/code25, signing lineage, and 16 KiB alignment. APK: 15,106,377 bytes; SHA256 `a065a0812663ef26eb9a87195ea6081da580d473bf13f463356fb33d49ed9221`. Core source was unchanged from the 48-test run; final Java/Activity changes were compiled and exercised again.
- Pixel 10 Pro upgraded without uninstalling; the original baseline digests for 2,333 messages and 765 memories, UID, registry and readable credential presence were retained. The signed Release startup probe passed ONNX inference/Activity startup and read all 791 pending original messages in one batch. Startup diagnostics temporarily pause automatic AI processing and restore its prior setting; a transient ADB daemon failure was retried successfully.
- Normal app launch then automatically processed the live 791-message backlog with the selected model/effort. The app was moved to the background while work ran. `ConversationMemory` reported `Completed batch messages=791 processed=2333 total=2333 elapsed_ms=145359`, followed by a 4,998 ms reflection-only pass. A read-only follow-up confirmed pending jobs 0, processed-through 2,333, 29 episodes, and an empty error status. No conversation text or credentials were emitted by diagnostics. Natural-language summaries were not manually reviewed for semantic accuracy.
- Android may defer jobs under power/network/quota restrictions; explicit force-stop requires reopening the app. Larger-than-budget input remains split and retryable. These are platform-controlled jobs, not an unrestricted permanent service. Source reference: https://developer.android.com/develop/background-work/background-tasks/persistent .

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

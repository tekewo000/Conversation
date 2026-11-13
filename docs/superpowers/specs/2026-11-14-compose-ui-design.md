# Compose UI migration

Replace every app-rendered Android View and Dear ImGui screen with Jetpack Compose Material 3. Preserve the dark, content-first chat, 16sp composer, per-person drafts, all people/import/delete flows, ChatGPT OAuth/model/effort/fast/usage/retry controls, display settings, Git token/sync and ZIP export. Android system document pickers and OAuth browser remain platform activities.

Keep the Odin SQLite/memory engine and existing Java network/storage classes. MainActivity remains the coordinator and extends ComponentActivity; ComposeUi.kt owns observable UI state and composables. No AndroidView, GLSurfaceView, widget dialogs or native renderer remain. JNI supplies bounded 80-message snapshots with page offsets; Compose owns selectable, accessible text, scrolling, IME insets and partial streaming text. Snapshot updates do not continually poll when idle.

Retain application ID, MainActivity preference file/keys, DB format, Keystore aliases, release signing key and signing lineage so installing the release preserves existing data. Token form state is not saveable and uses a secure dialog window. Existing validation, busy guards, cancellation and error handling stay in the coordinator.

Adopt a pinned Gradle/Kotlin/Compose toolchain for Android code and packaging; preserve existing PowerShell Odin/CMake builds and external signing verification. Release version 0.4.0, versionCode 7. Publish source plus signed arm64 APK to the existing GitHub repository.

Validate core/native/connection/signing tests, Compose UI instrumentation compilation, release manifest/signature/native-page alignment and APK contents. Run UI instrumentation on a connected device if available; otherwise explicitly report that runtime/UI feel and real authenticated requests were not verified.

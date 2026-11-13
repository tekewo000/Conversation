# Android Conversation: first runnable milestone

The user approved an Android app with Odin domain logic, Dear ImGui GUI,
native Android Japanese input, durable local memories, and eventual ChatGPT
subscription integration and Git history. Their next step is to connect a
phone and judge the first runnable build.

## Deliverable

An ARM64 debug APK, Android 10/API 29 or newer, installed with adb. This
milestone supports native Japanese/emoji input, sending text into a scrollable
conversation, reopening saved conversations, and manually saving messages as
memories. A single message is limited to 65,536 UTF-8 bytes; rejected text stays
in the input field. It is explicitly labelled as not connected to AI. No synthetic
assistant response, billing, network credential, or remote repository mutation
is part of this milestone.

## Architecture

- Odin owns conversation state, validation, durable serialization, and memory
  pinning. A small C ABI is called serially from a native bridge.
- Dear ImGui displays conversation and memory views using OpenGL ES 3. The
  first renderer uses a small C++ JNI bridge and upstream ImGui directly;
  an additional Odin ImGui binding is not needed for this milestone.
- A Java Activity hosts GLSurfaceView and an Android EditText/send button.
  This is OS/input glue, not Compose. Java byte arrays carry UTF-8 across JNI
  so supplementary characters do not pass through modified UTF-8.
- Native rendering runs on demand. Surface recreation must rebuild GL objects
  without resetting Odin state. Keyboard and app backgrounding preserve drafts.
- Human-readable conversation/memory files live in app-private storage.
  Failed writes cannot be reported as successful sends. Corrupt history cannot
  be silently replaced. Credentials and personal data never enter this source
  repository. The future personal memory repository is separate and private.

## Verification

Desktop Odin tests cover Japanese/emoji/newline round trips, empty input,
duplicate memory pins, reopening, malformed history and failed saves. Build
the ARM64 library and APK, verify the package/signature/ABI, and provide an
adb install script. Phone checks cover IME composition, copy/paste, touch
scrolling, process recreation, background/foreground and idle power.

## Next milestones

After this input/storage baseline is confirmed: validate supported ChatGPT
sign-in on Android, stream real responses, extract source-linked memories,
retrieve relevant facts, and sync text snapshots to a private GitHub repo with
head checks and conflict preservation. These are not claimed as implemented
by the first APK.

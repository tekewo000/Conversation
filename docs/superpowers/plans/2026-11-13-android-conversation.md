# Android Conversation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce an installable Android APK for Japanese conversation input, Odin-owned local history and manually pinned memories.

**Architecture:** A Java Activity hosts native input and a Dear ImGui GLES3 surface. A small JNI bridge serializes access to a portable Odin C ABI; Odin saves validated state before reporting success.

**Tech Stack:** Odin, C++/JNI, Dear ImGui, OpenGL ES 3, Android SDK/NDK, Java.

**Spec:** `docs/superpowers/specs/2026-11-13-android-conversation-design.md`

## Global Constraints

- Android 10/API 29 or newer; ARM64 debug APK.
- No fake AI replies or remote writes; visible AI-not-connected label.
- UTF-8 crosses JNI as byte arrays. Personal records remain in app-private storage.
- Existing history survives malformed input, storage errors and surface recreation.
- On-demand rendering; native Android Japanese composition/input.
- One message is at most 65,536 UTF-8 bytes; rejected text is retained.

## Review Focus

- Japanese supplementary characters, multiline text and quotes round-trip unchanged.
- Blank/overlong input is rejected with the draft preserved.
- Failed/corrupt storage does not silently destroy previous conversations.
- Repeated memory pinning does not duplicate records.
- Background/surface recreation does not lose records or crash on old GL objects.

## Task 1: Portable Odin state and persistence

Files: `core/`, `native/conversation_core.h`.
Interface: conversation_init/send/message_count/message_text/remember/memory_count/memory_text/error/close C ABI.

- [x] Write and run failing state/storage tests.
- [x] Implement UTF-8 validation, durable storage and memory pins.
- [x] Run the serialized strict Odin suite and C ABI checks; record results.

## Task 2: Native UI and Android input

Files: `native/native_ui.cpp`, `native/CMakeLists.txt`, `vendor/imgui/`, `android/`.
Interface: Java static nativeInit(byte[],byte[]), nativeSend(byte[]), nativeSurfaceCreated(), nativeResize(int,int,float), nativeRender(), nativeTouch(int,float,float), nativePause().

- [x] Build Activity with native input and on-demand GLES view.
- [x] Render conversation/memory views with upstream ImGui and pinned-message buttons.
- [x] Preserve draft, expose errors and handle lifecycle/context recreation.

## Task 3: Reproducible APK and handoff

Files: `scripts/setup-android.ps1`, `scripts/build-android.ps1`, `scripts/install-android.ps1`, `README.md`.

- [x] Provision workspace-local toolchain and pinned source dependencies.
- [x] Compile Odin object, native shared library, Java/dex and signed debug APK.
- [x] Run core suite and validate APK signature, manifest and ARM64 contents.
- [x] Review changes independently and fix substantive findings.
- [x] Document install commands, exact validation limits and next milestone.

## Execution notes

User explicitly requested implementation after approving the architecture and
will attach an Android phone after the APK is ready. Parallel work is split by
exclusive file ownership (Odin core / native rendering / toolchain); the root
integrates the Java host and validates the entire APK.

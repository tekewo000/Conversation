# Long-Term Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect real ChatGPT conversation to bounded SQLite storage and automatic source-linked memory.

**Architecture:** Odin owns SQLite and memory validation; C++ renders bounded chat pages and bridges JNI; Java handles native input, browser OAuth and background requests. SQLite is the operational source of truth and text archives support Git backups.

**Tech Stack:** Odin, SQLite C API, Dear ImGui/OpenGL ES3, Android Java, official SIWC OAuth and Responses streaming.

**Spec:** `docs/superpowers/specs/2026-11-13-long-term-memory-design.md`

## Global Constraints

- Android API29 ARM64; preserve current dark chat-only main UI and typography preferences.
- Never delete legacy notebook.json or commit personal records/tokens to public Git.
- Bounded cache80 messages; context/retrieval and extraction jobs bounded independently.
- Original evidence and temporal versions retained; untrusted model data validated.
- Previously selected subscription login; no new API-key flow or credential reuse.

## Review Focus

- Migration/failed write leaves the old data intact and new state transactional.
- Invalid/duplicate memory operations cannot mutate unrelated subjects/scopes or complete a failed job.
- Long history does not increase normal render/window/context sizes without bound.
- OAuth refresh/invalid state/rotating credentials cannot leak tokens or silently use another account.
- Failed/partial model output and lifecycle interruptions do not become committed assistant facts.

## Tasks

- [x] Core: tests first, SQLite FFI and migration, bounded pages/context, durable extraction jobs, memory validation and text export. Files core/, native/conversation_core.h, vendor/sqlite/, scripts/setup-sqlite.ps1, scripts/test-core.ps1.
- [x] Connection: standalone secure OAuth/token/SSE client and synthetic tests. Files ChatGptConnection.java and helpers/tests; no MainActivity edits by this worker.
- [x] Renderer: test-first bounded paging, role bubbles and provisional assistant output; JNI for core operations. Files native/native_ui.cpp, native/tests/ui_test.cpp.
- [x] Integration: CMake/build tools, Java worker/prompts/login/menu/status/model controls, automatic extraction and retry, memory view. Root owns remaining files.
- [x] Verify desktop suites, large-history tests, synthetic connection tests, APK and connected-device data-preserving upgrade; prepare live sign-in handoff only when app is reviewable.

## Execution

The user explicitly approved implementation. The independent storage, connection and
renderer domains use exclusive file ownership under the parallel-agents skill; root
integrates shared boundaries and validates the assembled result. No new design approval
round is required for the already selected architecture.

## Verified outcomes and rulings

- SQLite/FTS5 replaces full JSON rewrites; raw storage is authoritative and Git backup is chunked text export. No personal remote was configured.
- Only interpretations accept inferred certainty; facts/events/promises require explicit evidence attribution. Native validation enforces this independently of prompts.
- Original legacy file retained; unknown schema or missing initialization marker with existing rows fails closed.
- Model discovery is lazy inside transport, after the user record is durable. Full current user text replaces any truncated context snippet.
- Key discovery precedes token refresh; verified rotations are committed before honoring ordinary cancellation. Unspecified earliest_refresh_at semantics are not guessed.
- Android background DNS failed during token exchange; final exchange waits for returning to the singleTask app. Callback page provides a package-targeted return link.
- Real subscription responses omitted Content-Type. Completion is validated from SSE or a completed JSON response, never inferred from an HTTP200 alone.
- Live fictional blue-flower fact was sourced and stored as fiction. A later red-flower preference superseded it while preserving blue as historical.
- Long-run Japanese companion quality, embedding retrieval, automatic GitHub sync and restore UI remain outside this minimal implementation.

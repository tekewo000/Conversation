# Person Import and Repository Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a person from a Markdown file, isolate their conversations, and back them up to exactly one private GitHub repository.

**Architecture:** Pure Java parsing/registry stages file-based people; Odin transactions import profiles and sourced memory into per-person SQLite. JNI switches the active store safely. A bounded transaction archive feeds a private-repository GitHub client on a separate network worker.

**Tech Stack:** Existing Odin/SQLite/ImGui/Android Java, Android document picker/Keystore, GitHub HTTPS APIs.

**Spec:** `docs/superpowers/specs/2026-11-14-person-repository-design.md`

## Global Constraints
- Keep existing data, chat-only dark main UI and saved typography.
- One stable person UUID per private repository; no public uploads, forced pushes or secret exports.
- Source Markdown512KiB/profile32KiB/seeds512; incremental archives bounded and transactional.
- Creation/switching cannot race with response/extraction persistence.

## Review Focus
- Parser must not invent identity or confuse user/person statements.
- A staged/failed import must not replace the active registry or old database.
- URL case/aliases and numeric repository IDs must not bypass one-to-one rules.
- Network uncertainty must not duplicate archive batches or overwrite a foreign head/person.
- Person switching while network sync continues must not upload data to another repository.

## Tasks
- [x] PersonDocument/PersonRegistry and JVM tests; exclusive worker-owned files.
- [x] Core v2 profile/document import, cursor archive/replay and real SQLite tests.
- [x] Private GitHub backup/token storage and deterministic HTTP fixtures.
- [x] Root JNI, person creation/switch UI, persona context and bound automatic sync.
- [x] Full suites, APK, device-safe upgrade and read-only/live boundaries documented.

Validation:19 Odin tests plus C ABI roundtrip, native suite, seven JVM suites,
signed APK, real Android synthetic-person/Keystore/export instrumentation and
unchanged pre-existing phone data. Actual private GitHub writes await the user's
repository/token configuration; no credentials were copied from PC tooling.

User explicitly approved implementation after format/design discussion. Workers
own independent files; root integrates the shared ABI and validates the result.

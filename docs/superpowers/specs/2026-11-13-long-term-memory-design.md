# Long-term conversation and automatic memory

The user approved implementation of the researched design: automatic source-linked
memory, facts separated from interpretations, temporal updates, relevant retrieval,
SQLite storage, and text archives for Git. Normal use is conversation only; no manual
pin or whole-history list/copy. Retain the current dark, chat-first interface.

## Storage and migration

Odin owns the domain and a SQLite database in app-private files. Messages have stable
IDs, user/assistant roles, timestamps and original UTF-8. Append individual records;
never rewrite or load the entire history on ordinary startup/send. SQLite transactions
protect consistency. Preserve notebook.json and import it once transactionally, including
legacy pin data as legacy records rather than invented semantic memories. Corrupt input,
unsupported schemas or failed migrations must not leave a partially active database.

The render cache is bounded (at most80 messages). Page by stable message IDs/indices.
The accessible recent-text alternative is also bounded. Never send full history to AI.

## Memory model

Keep immutable versions of events, facts/current information, promises and interpretations.
Each record has subject, key, text, scope(real/fiction/shared), certainty(explicit/inferred),
source message IDs and a valid-from time where known. Supersession preserves history and
must match subject/key/kind/scope; an interpretation cannot overwrite a fact. Validate
source IDs and schema before committing untrusted model output. A source reference is
traceability, not proof that the model's interpretation is true.

Retrieval returns bounded recent turns, matching memories and relevant source context.
Current facts and active promises get priority; historical queries can retrieve old
versions. First implementation uses structured/temporal/lexical Japanese retrieval;
embedding-based semantic retrieval is an explicitly separate extension to measure.
Maintain a compact friend-persona prompt separately from changing experiences.

## Automatic processing

Persist the user message before network access. Stream a real assistant response as a
provisional bubble; persist it only on a completed response. Partial/error outputs are
not memory evidence. Durable jobs cover unprocessed message ranges in bounded batches.
After response completion, extract only new messages into the typed memory schema.
Validate and commit memory versions and job completion together. Job replay is idempotent;
failed extraction leaves the job retryable and cannot destroy the raw conversation.
The latest raw turns cover the gap while a background extraction job is pending.

No user confirmation/pin is required for ordinary extraction. Memory UI is a bounded,
read-only recent/search view with clear separation of explicit facts and inferred material.
Do not force retrieved facts into every answer or claim a memory is verified just because
the AI generated it.

## AI connection

Use the user's previously selected ChatGPT subscription via official Sign in with ChatGPT
OSS OAuth. Browser sign-in, loopback callback, PKCE/state/nonce, issued client registration,
ID-token signature/issuer/audience/expiry verification, encrypted Android Keystore token
storage, refresh and sign-out. No unrelated existing credentials or new API key. Tokens,
private conversations and databases never enter the public source repository.

Discover account-specific models; use the public Responses API with store:false and
stream:true. Bound context and avoid unsupported fields. Model calls happen on a worker,
not the UI or GL thread. The user completes browser authentication themselves only after
the app is built. Keep login/model/status/usage controls inside the menu.

## Git archive

Export consistent UTF-8 JSONL chunks with stable IDs and manifest metadata to app-private
export/. This supports restoring data and later private Git synchronization. Do not commit
personal records to this public application repository or silently configure a remote.

## Verification

- Legacy migration once, exact Unicode/roles/sources preserved, corrupt data preserved.
- At least20,000 stored messages; bounded render cache and bounded retrieval/context.
- Invalid memory payload/source/kind/supersession rolls back and leaves job pending.
- Duplicate job application does not duplicate memories, versions retain old evidence.
- Source-grounded current/past facts, fiction/real separation and Japanese retrieval cases.
- OAuth/SSE parsing and security validation tests using synthetic tokens/data only.
- APK build/signature/API-floor checks, actual-device upgrade and migration checks.
- Real network/quality validation requires the user's successful sign-in; do not fabricate
  an AI reply or describe fixture tests as live model/long-term companionship evidence.

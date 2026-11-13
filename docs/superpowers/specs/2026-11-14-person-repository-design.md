# Person files and one-to-one private Git repositories

The user approved Markdown person-file import and a one-to-one relationship
between each person and a Git repository. Normal operation remains chat-only.
Git is a one-way backup/change-history destination; remote edits are conflicts,
not silently merged into the active person.

## Person creation

Read conversation-person-v1 Markdown via Android's document picker. Preserve
the original and parse a compact always-used profile from basic identity,
speech, thinking and relationship sections. Import episodes/promises as sourced
handover claims, not invented historical chat turns. Mark inferred meanings
separately from explicit reported events; unknown reality is never asserted real.
Limits:512KiB original,32KiB core profile,512 seed records. Unknown fields remain
unknown. Preview name/profile before creation.

Each person receives a stable UUID and an isolated SQLite directory under
files/people/<uuid>. Existing root conversations remain a separate legacy person.
Choose an existing private GitHub repository before activating a new person.
Normalize URLs and enforce uniqueness locally by repository name and numeric ID;
verify the remote manifest's person UUID too. Bindings are immutable after creation.
Public or foreign-person repositories must be rejected before writes.

Stage files, verify the repository, import SQLite transactionally, then commit
the registry/active person. Failures preserve the previous active person and all
existing data. Switching/importing is serialized against model requests; a reply
or background extraction must never be saved into another person's database.

## Persona and provenance

Odin context includes the active person's compact profile every turn. Imported
documents and section references are first-class evidence. New actual chat keeps
ordinary message sources, and can supersede compatible sourced handover facts
without deleting the old versions. The user's own attributes and another person's
voice are not mixed. Original import content remains available in backups.

## Git archive

Store raw person.md, compact persona.json, incremental UTF-8 JSONL transaction
records and a versioned archive manifest. Stable person UUID and remote repository
ID are in the manifest. SQLite caches/indexes, credentials and UI state are excluded.
No random full-snapshot directory is added at each sync. Archive reads are cursor
based and bounded; transaction envelopes prevent half-applied memory versions.

Use GitHub HTTPS APIs with a user-provided fine-grained token restricted to the
selected repositories (Contents read/write, Metadata read). Store it encrypted
in Android Keystore-backed no-backup storage; never log/export it or borrow the
development PC's credentials. A secure app input configures it when the user is ready.

Check private flag, numeric repository ID, person manifest, and expected branch
head before committing. Empty repositories get an incomplete identity marker
followed by a complete atomic archive commit; retries recover uncertain results.
Never force-push or automatically merge external changes. Offline sync failure
does not affect local chat. Automatic sync is debounced and runs on a separate
network worker after a person-bound local archive batch is captured.

## Validation

Parser/registry tests cover actual Markdown fields, Japanese/UTF-8, blanks,
inference, URL aliases, one-to-one identity and failure-safe state files. Core
tests cover profile context, document sources, no fake old messages, isolation,
v1 migration, import rollback, incremental archive and replay. Git transport
fixtures cover public rejection, person mismatch, conflicts, empty initialization,
lost responses and cursor idempotence. APK and existing-device data are verified.
Live private writes require an actual selected person/repository/token; fixtures
are never described as successful user GitHub backup.

# Experience-backed persona evolution

The user approved implementing the researched proposal: preserve the same person while allowing experience-backed preferences, relationships, outlook, speaking style, and disposition to change and persist.

Keep the imported profile immutable as the starting point. Store append-only `persona_revisions(id, through_id, changes, created_at)` separately. Each revision contains a bounded JSON array of field patches with text, reason and original message IDs. An empty array advances the reviewed checkpoint without inventing growth. Current fields are the newest accepted patch per field and accompany every reply outside topic-based memory retrieval. Mood and temporary roleplay stay in ordinary conversation context.

Use the existing model transport after memory extraction, at most one reflection per invocation. Review after 12 new processed messages, advance by at most 24 messages, include up to 64 older/new messages under a byte budget and sourced current memories independent of the current topic. Retry failures without changing the last accepted state.

Core validation rejects unknown/duplicate fields, invalid IDs, repeated evidence, out-of-window evidence, stale requests, oversized strings, and unsupported updates. Preferences/relationship need two user and two assistant messages. Outlook/style/disposition need six of each spanning at least 24 message IDs and a 24-message cooldown. Every patch needs a new user and assistant message since the last review. These are conservative initial turn-based heuristics, not research-established rates or proof of independent experiences. The reflection prompt must distinguish user attributes, temporary acting, circular self-claims, and actual shared interactions; semantic evidence quality still depends on the model.

Revision history and checkpoints participate in existing atomic Git transaction archives and ZIP snapshots. Validate replayed updates against their original messages; old archives without persona revisions remain valid. No credentials, live remote writes, forced installation, or model training are required.

Validation covers topic-independent state inclusion, preservation of unchanged fields and source profile, fast/slow gates, cancellation/retry, malformed/duplicate/stale updates, restart/isolation, archive replay and ZIP export. Mock model responses prove orchestration, not subjective personality growth. Longitudinal Japanese conversation and device load remain separate evaluation.

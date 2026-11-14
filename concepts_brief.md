# Memory and persona: preliminary assessment

* **Author:** tekewo000
* **Date:** 2026-11-14
* **Stage:** The author requested implementation after reviewing the research shortlist, then authorized a signed v0.6.5 release and an update preserving the phone's existing data. The changes add source-backed relationship state to existing continuity and connect its evidence to replies, extraction, and persona reflection. Implementation and release checks are recorded in `docs/validation.md`. No EGM Goldfish review has run.

## Overview

The question is whether the current short-term memory, long-term memory, persona formation, and persona change work together as a continuing person, rather than only collecting techniques from different papers.

The current implementation separates original messages, selected long-term memories, ongoing topics and episodes, current feelings, an imported starting profile, and revisions to the current persona. These parts have distinct roles. The main unresolved issue is whether their combined effect produces appropriate recall and justified changes in actual conversation. Storage correctness cannot answer that question.

The author clarified the intended experience: the relationship can deteriorate and recover; the character can gradually become kinder and gradually open up. Successful change therefore includes both difficult relationships and repair, rather than a mandatory increase in affection. The triggers, persistence, and observable behavior of those changes still need clarification.

This assessment uses the working files, including uncommitted changes, rather than assuming the latest public APK has identical behavior. No private conversation database was inspected.

## Analysis

### Short-term memory

`core/conversation.odin:549` assembles up to 61 recent messages, with each past message excerpted to 2,048 characters. `ChatSession.reply` restores the current user input in full. Relevant older originals have a separate budget. Ongoing topics and episodes are always supplied, and the current feeling is saved with the reply.

This should support recent references, pending decisions, and emotional continuity better than a summary alone. However, 30 exchanges are a context limit, not an evaluated span of understanding. Long messages can lose decisive details. Topics and episodes are updated asynchronously, so the next reply may use an older summary together with newer raw messages. The prompt asks the model to resolve this ordering; it does not mechanically guarantee the interpretation.

### Long-term memory

The core stores facts, events, promises, and interpretations with original message IDs, time information, and real/fiction/shared scope. Updates can supersede matching old records while retaining history. Replies combine lexical search and on-device semantic candidates, then recover related originals. These choices make failures traceable and preserve evidence.

Retrieval remains bounded: at most 12 selected memories, a 10,000-byte memory budget, and excerpts of the originals. Embeddings use at most 256 tokens. The semantic query uses the current input; lexical retrieval uses its first 64 code points. There is no explicit rewriting of an ambiguous current input using earlier dialogue in the inspected reply path. A question such as "Would that still work for me?" can therefore fail to retrieve a relevant old experience even when storage and vector search work as designed. This is a risk inferred from the pipeline, not a measured failure rate.

Memory updates also depend on model interpretation: stable keys, the right superseded record, and the distinction between explicit information and inference are not established merely by valid JSON or a valid source ID. A mistaken interpretation can remain a well-formed, source-linked memory.

### Persona formation

`ChatSession.replyInstructions` combines the imported profile with the latest persona fields on every reply, independently of topic search. Without an imported profile, the starting instruction is a broadly defined close friend. `core/persona.odin:14` reconstructs the newest accepted value for each of five fields: preferences, relationship, outlook, speaking style, and disposition.

This is a persisted description used to condition the model. The inspected path does not train model weights. It can still produce a distinct continuing character, but storing a description and producing consistent behavior are separate outcomes. The starting profile remains particularly important when experiences have not yet produced accepted revisions.

### Persona change: the least established part

Reflection becomes eligible after 12 new processed messages, advances by at most 24, and sees at most 64 originals excerpted to 512 characters within 40,000 bytes. It also sees up to 16 recent current memories about the assistant or relationship. Preferences and relationship need at least two sources from each role. Outlook, speaking style, and disposition need six from each role, a 24-ID span, and a 24-message cooldown. No-change results are valid.

These conditions limit update frequency and reject malformed or stale changes. They do not establish that the evidence contains independent experiences, that a change is proportionate, or that the revised persona will behave accordingly. A repeated exchange in one sitting can satisfy message-count conditions. An assistant can also generate behavior, cite that behavior during reflection, and then reinforce the resulting description. The reflection prompt explicitly discourages this circular pattern, but semantic enforcement remains with the model.

Each field update replaces that field's current text. The prompt asks the model to preserve unrelated details, but the core does not compare their meaning. A preferences revision can gradually omit an older preference without any explicit decision to abandon it. Retained history supports diagnosis; it does not prevent this loss from the current prompt.

The strongest counterargument to adding stricter rules is that they can freeze a character and reject meaningful single experiences. Conversely, faster updates can turn agreement, temporary acting, or recent mood into a durable trait. There is no justified universal rate yet. The desired experience must determine which errors matter most.

### How the research applies

* [MemGPT](https://arxiv.org/abs/2310.08560) supports separating always-present context from external history. It does not establish that this application's selection limits are adequate or that its persona changes are justified.
* [LongMemEval](https://arxiv.org/html/2410.10813v2) separates extraction, cross-session reasoning, temporal reasoning, updates, and knowing when information is absent. Its experiments also show information loss from compressing history into facts. Preserving originals here is useful, but originals must still be retrieved and read.
* [When Users Don't Ask](https://aclanthology.org/2026.findings-emnlp.1289/) reports gaps between explicit question answering and memory use inside conversation, and between retrieval and final response quality. This is directly relevant to the current-input retrieval path.
* [Generative Agents](https://arxiv.org/abs/2304.03442) evaluates memory and reflection in a simulated social environment. Its evidence for believable behavior does not validate enduring identity in a Japanese one-to-one companion.
* [Beyond Static Persona Consistency](https://aclanthology.org/2026.acl-long.1336/) distinguishes stable identity from appropriate adaptation and represents different time scales. That distinction fits the question here. The inspected implementation already separates mood from enduring fields, but it has no separately evaluated process for accumulated meaning between them. Adding such a process is an untested option, not an automatic requirement.

These sources justify specific questions and design choices. Their reported scores cannot be added together or transferred to this implementation. A combined system needs its own evaluation, including cases where components conflict.

### Research shortlist after clarifying the intended relationship

1. **First for implementation: [Beyond Static Persona Consistency: Dynamic Persona Coherence in LLM Role-Playing](https://aclanthology.org/2026.acl-long.1336/) (ACL 2026).** The distinction above is directly relevant to withdrawal after repeated provocation. Important limits: the core identity is held stable, so this is not evidence for gradual enduring kindness; the main evaluation is Chinese and relies on a model judge. See the [paper's limitations](https://aclanthology.org/2026.acl-long.1336.pdf).
2. **First for the relationship experience: [Memory-Driven Self-Disclosure and Relational Turning Points](https://arxiv.org/abs/2607.14593) (ICMI 2026, acceptance reported on the paper page).** Twenty-four participants used a memory-augmented agent for ten sessions. The study distinguishes enjoyable individual sessions from continued relationship development and reports both gradual accumulation and abrupt turning points. This concerns users' experience of the relationship, not proof that the agent develops trust. It is one small study of one voice agent; its associations are not an established causal recipe for this text app.
3. **First for evaluating enduring change: [Do AI Personas Grow?](https://arxiv.org/abs/2608.06485) (2026 preprint).** BFI-Adapt examines personality responses to major life events. Its finding that changes can occur without matching the event-specific pattern is useful: detecting movement alone is insufficient. The study uses synthetic personas, immediate before/after measures, and only a short intervening dialogue. It does not establish gradual growth over months or reconciliation after everyday disagreements.

None of these three establishes a complete process for reciprocal conflict, repair, and slow character growth. The research supports treating those as distinct outcomes rather than calling every warmer reply development.

### Appraisal of the app itself

The implementation has substance beyond a character prompt: sourced originals, retained revisions, local hybrid retrieval, feelings saved with replies, and persona state available outside topic search. Connecting these in a usable Android app is concrete engineering work. The research value is the opportunity to observe whether remembered shared experiences affect later relationships and behavior.

The number of cited papers is not a measure of quality or novelty. The strongest current claim is that this is an implemented, research-informed prototype for continuing characters. A claim of superior companionship, a novel personality mechanism, or demonstrated natural growth needs comparative and longitudinal evidence that has not been provided here. The next valuable evidence would show a specific remembered event affecting later behavior appropriately, including after reconciliation and across unrelated topics.

## Goals vs. Non-Goals

* **In-Scope Goals:** Assess actual recall, preservation of identity, justified persona changes, and interaction between memory layers. Support the intended experience of conflict, reconciliation, gradual kindness, and gradual openness. Clarify what experiences cause these changes and how they appear in behavior.
* **Out-of-Scope Non-Goals:** Implement a new memory framework, add a graph database, train a model, change update thresholds, or inspect private conversation content. Release packaging and the data-preserving update were authorized separately after implementation.

## Alternatives Weighed

No alternative has been rejected by the author yet.

Adding more paper-derived mechanisms could address a demonstrated weakness, but also introduces additional opportunities for contradictory summaries and self-reinforcing state. Freezing the persona would reduce accepted changes but defeat growth if growth is the goal. Increasing context could preserve more detail while increasing request cost and would not itself prove appropriate use. These remain trade-offs to evaluate, not approved changes.

## Key Decisions & Staging for Next Steps

The provisional assessment is: the system has a coherent separation of storage and state, but natural recall and experience-backed persona change are not established by the inspected tests. Persona change deserves the most scrutiny because source counts do not validate the meaning of a change.

The author's clarification makes one distinction central: a current feeling, a relationship with this user, and a more general disposition need not change together. A character may stop being angry before trusting the user again, or become more open with this user while remaining reserved elsewhere. These are working examples for discussion, not approved transition rules. A single friendliness score or a fixed number of positive exchanges would not by itself establish the intended experience. The next clarification concerns what interactions should cause the character to withdraw or open up.

Before choosing changes, clarify what experience the user wants to preserve or develop, and what currently feels wrong. Then evaluate the same conversation trajectories with and without relevant components. Score retrieval of required evidence separately from grounded replies; score identity preservation separately from justified adaptation. Include no-change conversations, repeated persuasion, fiction, corrections, ambiguous references, long gaps, and meaningful experiences. Inspect whether a persona revision changes later behavior across unrelated topics.

These are candidate evaluation directions, not an approved test plan or completed benchmark. Existing source tests were read; they were not rerun. No live inference, device run, or long-term conversation evaluation was performed. Goldfish review remains pending the skill's explicit readiness checkpoint.

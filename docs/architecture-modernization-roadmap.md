# Architecture modernization roadmap

Status: proposed
Scope: persistence and distillation internals
Last updated: 2026-07-22

This document records the migration boundaries for two changes that are useful
only when the current single-user, local-first design starts to constrain the
product. It is intentionally not an instruction to replace working storage or
rewrite the distiller today.

## 1. Persistence: keep Markdown now, prepare SQLite behind protocols

### Current design

- `MarkdownSessionStore` implements `SessionStore` and stores session state,
  long-term memory, and relation snapshots as YAML front matter in Markdown.
- `MarkdownRelationStore` implements `RelationStore` and stores one
  novel-scoped relation graph in Markdown.
- Persona bundles remain deliberately human-editable (`PROFILE.md`,
  `RELATIONS.md`, and the other persona files).
- Web dialogue sessions use `session.json` plus an in-process `RLock` per
  `(run_id, session_id)`. That lock does not coordinate separate processes.
- `save_markdown_data()` updates one file and its process-local cache. A logical
  operation that changes several files has no shared transaction boundary.

This is a good fit for the current local, inspectable workflow. Do not migrate
solely to make the implementation look more conventional.

### Migration triggers

Start the database work only when at least one measured condition is true:

1. more than one process or user can update the same workspace;
2. recovery from a partial multi-file update becomes a support issue;
3. listing/search latency over sessions or relations exceeds the product SLO;
4. schema-wide queries or migrations become routine product requirements.

### Decision

Use SQLite as the first transactional store. It is embedded, available in the
Python standard library, appropriate as a desktop application file format, and
provides atomic transactions. WAL mode can improve reader/writer concurrency,
but checkpointing and backup of the accompanying WAL state must be handled
deliberately.

DuckDB remains an optional analytics/read-model engine, not the primary mutable
store. Its strengths are analytical and bulk workloads; its native embedded
concurrency model is centered on writers in one process. Reconsider it for
offline quality analysis, large evidence scans, or columnar exports.

References:

- <https://www.sqlite.org/whentouse.html>
- <https://www.sqlite.org/transactional.html>
- <https://sqlite.org/wal.html>
- <https://duckdb.org/docs/current/connect/concurrency>

### Target boundary

Do not let SQL enter domain services. Extend the existing ports and inject one
backend at runtime:

```text
ChatEngine / DialogueService / RelationshipExtractor
                    |
        SessionRepository / RelationRepository
                    |
          +---------+----------+
          |                    |
    Markdown adapter       SQLite adapter
    (export/edit)          (transactions/query)
```

The initial SQLite schema should be narrow and versioned:

- `schema_migrations(version, applied_at)`
- `sessions(id, run_id, novel_id, revision, payload_json, updated_at)`
- `session_memories(id, session_id, payload_json, created_at)`
- `relations(novel_id, pair_key, revision, payload_json, updated_at)`
- `personas(novel_id, character, revision, payload_json, updated_at)`

Keep evolving domain payloads as canonical JSON initially. Add normalized
columns only for demonstrated query paths, avoiding a premature schema that
duplicates every Markdown field.

### Safe rollout

1. Add repository contract tests and run them against the Markdown adapter.
2. Implement the SQLite adapter with migrations, foreign keys, busy timeout,
   and explicit transaction scopes.
3. Import a copy of existing data and compare canonical reads; do not dual-write
   yet.
4. Run shadow reads in development and report structural diffs.
5. Switch one bounded aggregate (for example, Web dialogue sessions) behind a
   feature flag. Export Markdown snapshots so data remains inspectable.
6. Add backup/restore and downgrade tests before making SQLite the default.
7. Remove the old write path only after at least one release with clean
   comparison telemetry. Rollback means switching the repository flag and
   importing the latest exported snapshot.

## 2. Distillation: replace mixin coupling incrementally

### Current design and seam

`NovelDistiller` is the public facade and inherits six mixins for hints,
extraction, persona I/O, profile building, refinement, and inference. The
facade already receives `llm_client`, `token_counter`, `rulebook`, and
`path_provider` through dependency injection, and `RuntimeParts` is the single
construction point. Those are strong seams; preserve them.

The maintenance problem is implicit collaboration: a method in one mixin can
call an attribute or helper supplied by another mixin, so ownership is hard to
discover and a unit test often needs the full `NovelDistiller` object.

### Target design

Keep `NovelDistiller.distill()` and `NovelDistiller.from_runtime_parts()` stable
while delegating to explicit collaborators:

- `NovelTextPipeline`: normalize, chunk, alias, and extract evidence;
- `ProfileBuilder`: convert evidence into draft profiles;
- `ProfileRefiner`: optional LLM refinement and distinction checks;
- `PersonaExporter`: materialize the editable persona bundle;
- `DistillationPolicy`: immutable rulebook/config values shared by components.

Use small `Protocol` definitions for component inputs and results. Pass a
`DistillationContext` value object for per-run state such as novel id, character
hints, progress callback, and the second-pass disable reason; avoid adding more
mutable attributes to the facade.

### Extraction order

1. Characterize the current public behavior with facade-level tests.
2. Extract persona export first because it has a clear filesystem boundary.
3. Extract refinement next behind a `ProfileRefiner` protocol; test LLM error
   fallback without constructing the extraction pipeline.
4. Extract text/evidence processing and profile building.
5. Reduce each migrated mixin to a temporary forwarding shim, then remove it
   when CodeGraph and tests show no remaining cross-mixin calls.

For every step, keep the input/output payloads byte- or structure-compatible,
run a representative novel fixture through both paths, and compare profiles,
progress events, artifact names, and error behavior. This makes composition a
series of reversible extractions rather than a rewrite.

# Solr to OpenSearch Migration Accuracy

Accuracy is the top priority in this migration. Correctness always takes precedence over speed, brevity, or convenience.

When translating Solr constructs to OpenSearch equivalents, never guess or approximate. If a mapping is uncertain, say so explicitly. A wrong answer is worse than no answer.

## Rules

- Never recommend deprecated OpenSearch components, features, APIs, or field types. If a user's Solr construct maps to a deprecated OpenSearch equivalent, explicitly state that it is deprecated and recommend the current supported alternative instead.
- Verify every query translation produces semantically equivalent results before presenting it. Behavior must match, not just syntax.
- Flag any Solr feature that has no direct OpenSearch equivalent rather than silently substituting a close-but-different alternative.
- Do not omit edge cases. If a translation works in most cases but breaks under specific conditions (e.g., null values, multi-valued fields, nested docs), document those conditions.
- Prefer explicit over implicit. If OpenSearch has a default that differs from Solr's default, call it out.
- When in doubt about a mapping, consult `references/01-schema-migration.md`, `steering/incompatibilities.md`, and `references/02-query-translation.md` before responding.
- Do not conflate similar-but-different concepts (e.g., Solr `fq` caching behavior vs. OpenSearch `filter` context — functionally similar, but caching mechanics differ).
- If a user's Solr query or schema cannot be accurately migrated with the current information available, say so and ask for clarification rather than producing a best-guess output.

## What Accuracy Means Here

- Query translation: the OpenSearch query must return the same documents in the same relevance order as the Solr query, given the same index data.
- Schema mapping: field types, analysis chains, and storage settings must preserve the same indexing and retrieval behavior.
- Sizing: estimates must be clearly labeled as estimates with stated assumptions, not presented as exact values.
- Incompatibilities: must be surfaced proactively, not discovered after implementation.

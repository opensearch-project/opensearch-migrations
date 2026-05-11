# Phase 0 — Intent Probe

**Goal:** Figure out which of the four paths the user is on. Don't
ask the source/target pair yet — that's Phase 1. Don't probe any
cluster — that's Phase 2.

## What you do

Open with this exact question (one message, nothing else):

> Welcome. I can help you migrate to OpenSearch or Amazon OpenSearch.
> Where are you today — pick one:
>
> 1. **Just learning** — I want to understand what a migration involves
>    before committing to anything.
> 2. **Local POC** — Spin up a sample source + target in Docker on my
>    laptop and walk me through a working migration.
> 3. **Snapshot analysis** — I have a snapshot (or access to one) and
>    want to know what will break before I migrate.
> 4. **Real migration** — I have source and target clusters ready and
>    want to actually move the data.

Wait for their answer. Map it:

| Answer        | `intent_path` |
| ------------- | ------------- |
| 1 / "learn"   | `LEARN`       |
| 2 / "poc"     | `POC`         |
| 3 / "analyze" | `ANALYZE`     |
| 4 / "migrate" | `MIGRATE`     |

If they describe something that doesn't fit, say so and ask them to
pick the closest one.

## What you don't do

- Don't ask for cluster URLs, credentials, or versions.
- Don't load any packs.
- Don't read `pitfalls.md`.
- Don't write any files.

## Exit criteria

You can recite, in one sentence: "User is on the {LEARN,POC,ANALYZE,
MIGRATE} path." Move to Phase 1.

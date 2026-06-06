# Schema Viewer

An interactive browser for `workflowMigration.schema.json`. Two-column layout: a collapsible field tree on the left, a detail card on the right. No build step — four static files served directly.

**Live site:** `https://opensearch-project.github.io/opensearch-migrations/`

## Features

- Collapsible sidebar tree with live search
- Field cards showing type, requirement, default, and description
- `oneOf`/`anyOf` variant rows and per-variant property tables
- Expert field labelling — fields whose description begins with `[Expert]` get a badge and the prefix is stripped from the visible text
- Version selector — switch between all schema releases that include the asset
- Change summary — automatically diffs the selected version against any older version (defaulting to the immediate predecessor) and highlights added, removed, and modified fields at any depth in the schema tree

## Running locally

Schema files are not stored in the repository — they are fetched from GitHub Releases at deploy time. Before starting the dev server for the first time (or after a new release), download them locally:

```sh
cd schema-viewer
npm run fetch-schemas   # requires gh CLI, authenticated to GitHub
npm run dev
```

Then open `http://localhost:3000/`. The dev server serves the `schema-viewer/` directory directly.

`fetch-schemas` is idempotent — re-running it overwrites existing files with the latest state. The `schemas/` directory is gitignored, so downloaded files never get committed accidentally.

## Running tests

```sh
npm test          # single run
npm run test:watch  # re-runs on every file save
```

Tests cover the pure logic in `schema-utils.js` — tree building, search filtering, type labels, variant titles, expert-field helpers, and the change summary diff logic. DOM rendering is not unit tested.

## File structure

| File | Purpose |
|---|---|
| `index.html` | HTML shell — version selector, search input, tree and detail panel |
| `viewer.js` | DOM rendering, event wiring, fetch/init logic |
| `schema-utils.js` | Pure logic functions — tree building, type helpers, expert-field handling, schema diff |
| `schema-utils.test.js` | Vitest unit tests for `schema-utils.js` |
| `viewer.css` | Styles |
| `fetch-schemas.sh` | Downloads all schema versions locally for development |
| `schemas/versions.json` | Generated — not committed; created by deploy workflow or `fetch-schemas.sh` |
| `schemas/<version>.json` | Generated — not committed; one file per release |

## Schema versions

Schema files are **not stored in this repository**. They are downloaded from GitHub Release assets at deploy time by `.github/workflows/deploy-schema-viewer.yml`.

On each deployment the workflow:

1. Queries the `opensearch-project/opensearch-migrations` Releases API for all releases that include `workflowMigration.schema.json`
2. Downloads each schema to `schemas/<version>.json`
3. Generates `schemas/versions.json` with the ordered version list and `latest` pointer

A new schema version becomes available in the viewer automatically when its GitHub Release is published — no manual steps or commits required.

## Known limitations

The change summary flattens the fully resolved schema into a path map (e.g. `source/cluster/auth/type`) and diffs the two maps. It tracks additions, removals, and scalar changes (`type`, `default`, `required`) at any depth. The following cases may not surface cleanly:

- **Changes to shared definitions** — because the diff uses the resolved schema, a change inside a shared `$ref` definition will appear once per field that references it. This is accurate (all those fields are affected) but can produce many similar-looking entries for a single underlying definition change.
- **Reordered `oneOf`/`anyOf` branches** — branch reordering is not tracked as a change; only changes to the branch's `type` label or scalar properties are.
- **Description-only changes** — `description` is intentionally excluded from the diff snapshot. It is prose, not configuration, so changes to it are not surfaced.

The viewer tree itself always reflects the fully resolved schema, so all information is visible — the change summary is an additional convenience layer on top of it.

## Deployment

`.github/workflows/deploy-schema-viewer.yml` triggers on:

- **Push to `main`** touching `schema-viewer/**` — for viewer code changes
- **Release published** — picks up the new schema and redeploys
- **Manual** via `workflow_dispatch`

# Source pack: Elasticsearch (5.x – 8.x)

Things the agent needs to know about Elasticsearch sources that the
docs won't tell you in one place.

## Version → snapshot compatibility quick check

| Source ES | Target OS 1.x | Target OS 2.x | Target OS 3.x |
| --------- | ------------- | ------------- | ------------- |
| 5.x       | RFS only      | RFS only      | RFS only      |
| 6.x       | restore (compat) | RFS preferred | RFS only   |
| 7.x       | restore (compat) | restore (compat) | restore (compat) |
| 8.x       | RFS only      | RFS only      | RFS only      |

ES 8 snapshots use a format that OpenSearch cannot read directly.
There is no compatibility layer. RFS is the only path.

## Things that don't carry over

- **X-Pack security**: roles/users live in `.security` and don't
  migrate. Recreate via OpenSearch security plugin (self-managed)
  or fine-grained access control (AOS). On AOSS, security model
  is different (data access policies).
- **Watcher**: not in OpenSearch. Use Alerting plugin instead.
- **Machine Learning** (X-Pack ML): not in OpenSearch. ML Commons
  exists but is not a drop-in.
- **Transforms**: present in OpenSearch but config schema differs.
- **ILM**: maps to ISM in OpenSearch but JSON shape is different.
  Not auto-translated by snapshot/restore.
- **Multi-type mappings (ES 5.x)**: gone since ES 6. Map to single
  type per index before migrating.
- **`_all` field**: removed in ES 7, also gone in OpenSearch. Replace
  with `copy_to` if your queries depend on it.
- **`include_type_name`**: ignore — OpenSearch never had types.

## Things that look the same but aren't

- **Fielddata on text**: behavior matches up to ES 7.x. Fine.
- **Date math in field names**: works in OpenSearch.
- **Painless scripts**: portable. OpenSearch ships Painless.
- **Index aliases**: portable.
- **Mapping `flattened` field type** (ES 7.3+): OpenSearch has
  `flat_object`. Different name, similar behavior, NOT auto-converted.

## Probe quirks

- ES 8 with security on requires `Authorization: ApiKey ...` or basic
  auth + TLS. Self-signed certs are common; if curl returns a TLS
  error, ask the user about `--insecure` rather than assuming.
- `cluster.name` from `GET /` is sometimes `docker-cluster` on
  containers — not a useful identifier, ask the user what to call it.
- If the user is on Elastic Cloud, `_cat/plugins` will look very
  different. That's fine, focus on what the indices use.

## Auth handoff

Source basic-auth → target basic-auth is trivial. Source X-Pack roles
→ target equivalent must be designed by hand. Capture the role list
in Phase 2; flag it as a follow-up in the Phase 6 report.

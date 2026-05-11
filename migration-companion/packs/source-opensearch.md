# Source pack: OpenSearch (1.x – 3.x)

Things the agent needs to know about OpenSearch sources.

## Version → target compatibility

| Source OS | Target self-managed OS 3 | Target AOS | Target AOSS |
| --------- | ------------------------ | ---------- | ----------- |
| 1.x       | snapshot/restore         | snapshot/restore | RFS  |
| 2.x       | snapshot/restore         | snapshot/restore | RFS  |
| 3.x       | snapshot/restore         | snapshot/restore | RFS  |

OpenSearch → OpenSearch within the same major or 1 major up is the
simplest case in this whole skill. Snapshot/restore covers it.
AOSS is the only awkward leg because it doesn't accept snapshot
restore at all.

## Things that don't carry over

- **Plugins** that aren't bundled on the target: list with
  `GET /_cat/plugins` in Phase 2 and check each against the target
  pack's bundled-plugin list.
- **k-NN indices**: vector field type and method config carry over,
  but `space_type`/`engine` defaults changed between OS 1 → 2. Re-read
  mapping after restore and verify queries return ranked results.
- **Anomaly Detection / Alerting / ISM** state: stored in dot-prefixed
  system indices (`.opendistro-anomaly-detector-*`, `.opendistro-alerting-*`,
  `.opendistro-ism-*`). Not migrated by default — the user has to
  re-create monitors/detectors/policies, or carry these specific
  indices in the snapshot scope.

## OS 1 → OS 3 specific gotchas

- Removed in 3.x: `mapper-murmur3` is no longer a separate plugin
  (now a feature flag). Mappings using it need re-checking.
- Default password policy on the security plugin tightened in 2.12+.
  If the user is restoring `.opendistro_security` from an OS < 2.12
  snapshot to OS 3, expect login failures until they reset.

## Probe quirks

- AOS source: `GET /_cat/plugins` will show AOS-specific plugins
  (`opensearch-ml`, `opensearch-knn`, `opensearch-security`). Don't
  count these as "extra" — they're part of the platform.
- AOS source: snapshots taken by AOS go to S3. The user has the
  bucket name; they don't always have direct S3 access. If they
  don't, the path is "have AOS register the snapshot repo on the
  target" rather than "copy snapshot files".

## Auth handoff

OS-bundled security plugin → same plugin on the target: the
`.opendistro_security` index can be carried in the snapshot, but
test before relying on it. AOS uses fine-grained access control
(maps cleanly). AOSS uses data access policies (does NOT map; design
from scratch).

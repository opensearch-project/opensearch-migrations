"""Workflow-facing operations for the RFS Reindex-from-Snapshot failed document stream.

The failed document stream is an append-only set of NDJSON.gz objects in S3, written by RFS workers
when terminal document failures occur. Records for a given backfill live under
``s3://<bucket>/<prefix>/session=<session_id>/``, where ``session_id`` is the owning
``SnapshotMigration``'s own UID — so each backfill (even multiple parallel ones in a single workflow)
has its own prefix and records are never mixed.

Bucket/region/endpoint/prefix come from a single source of truth: the config processor resolves them
before workflow submission and projects them onto the owning ``SnapshotMigration``'s spec. The console
reads those resolved fields, and the session id, directly from the ``SnapshotMigration`` it is reporting
on (via the Kubernetes custom-objects API) — so it always agrees with what RFS wrote. There is
intentionally no namespace-global ConfigMap (which could not represent multiple/parallel backfills).

The bucket is the stream's on/off switch and has no default: a migration that named none reports as not
configured here.

``--migration <name>`` selects which ``SnapshotMigration`` to inspect when several exist.

This module is intentionally a thin wrapper around the S3 listing/get APIs so a
customer can also inspect the failed document stream with the aws CLI if they prefer.
"""
from __future__ import annotations

import contextlib
import copy
import gzip
import hashlib
import io
import json
import logging
from dataclasses import dataclass
from typing import Iterator, List, Optional

import boto3
from botocore.exceptions import ClientError

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class FailedDocumentStreamConfig:
    bucket: str
    prefix: str          # always trailing-slash-terminated
    session_id: str
    region: Optional[str] = None
    endpoint: Optional[str] = None

    @property
    def session_prefix(self) -> str:
        return f"{self.prefix}session={self.session_id}/"

    @property
    def location_uri(self) -> str:
        return f"s3://{self.bucket}/{self.session_prefix}"


class FailedDocumentStreamNotConfigured(RuntimeError):
    """Raised when no session id / bucket is available from any source."""


# The SnapshotMigration CRD the console reads failed-document-stream config from.
FAILED_DOCUMENT_STREAM_API_GROUP = "migrations.opensearch.org"
FAILED_DOCUMENT_STREAM_API_VERSION = "v1alpha1"
SNAPSHOT_MIGRATION_PLURAL = "snapshotmigrations"

# The resolved failed-document-stream destination is projected onto the SnapshotMigration spec under
# these prefixed keys (config processor: prefixFields("documentBackfill", ...)).
_SPEC_BUCKET = "documentBackfillFailedDocumentStreamS3Bucket"
_SPEC_PREFIX = "documentBackfillFailedDocumentStreamS3Prefix"
_SPEC_REGION = "documentBackfillFailedDocumentStreamS3Region"
_SPEC_ENDPOINT = "documentBackfillFailedDocumentStreamS3Endpoint"

# Together these name exactly one documentBackfillConfig in the configuration that produced this
# backfill: migrationLabel is the per-snapshot entry's own label, sourceLabel/targetLabel the
# fromSource/toTarget it was declared under.
_SPEC_MIGRATION_LABEL = "migrationLabel"
_SPEC_SOURCE_LABEL = "sourceLabel"
_SPEC_TARGET_LABEL = "targetLabel"
_SPEC_TARGET_ENDPOINT = "targetEndpoint"


@dataclass(frozen=True)
class MigrationIdentity:
    """Which document backfill produced a failure-stream session.

    A redrive needs the session and the settings to come from the same run, or documents reach a
    cluster they were never destined for.
    """
    name: str
    migration_label: Optional[str] = None
    source_label: Optional[str] = None
    target_label: Optional[str] = None
    target_endpoint: Optional[str] = None

    def describe(self) -> str:
        return (f"SnapshotMigration '{self.name}' (backfill '{self.migration_label}' from "
                f"'{self.source_label}' to '{self.target_label}' at {self.target_endpoint})")


def _trim(value) -> Optional[str]:
    return value.strip() if isinstance(value, str) and value.strip() else None


def _list_snapshot_migrations() -> List[dict]:
    """List SnapshotMigration CRs in the current namespace ([] if the k8s client is unavailable)."""
    try:
        from kubernetes import client, config
        from kubernetes.client.rest import ApiException
        try:
            config.load_incluster_config()
        except config.ConfigException:
            config.load_kube_config()
        from console_link.workflow.models.utils import get_current_namespace
        ns = get_current_namespace()
        custom = client.CustomObjectsApi()
        resp = custom.list_namespaced_custom_object(
            group=FAILED_DOCUMENT_STREAM_API_GROUP,
            version=FAILED_DOCUMENT_STREAM_API_VERSION,
            namespace=ns,
            plural=SNAPSHOT_MIGRATION_PLURAL,
        )
        return resp.get("items", []) or []
    except ImportError:
        logger.debug("kubernetes client not available; SnapshotMigration lookup skipped")
        return []
    except ApiException as e:
        logger.warning("Failed to list SnapshotMigration resources: %s", e)
        return []
    except Exception as e:
        logger.warning("Failed to list SnapshotMigration resources: %s", e)
        return []


def _select_snapshot_migration(migration_override: Optional[str], items: List[dict]) -> dict:
    by_name = {it.get("metadata", {}).get("name"): it for it in items}
    if migration_override:
        sm = by_name.get(migration_override)
        if sm is None:
            available = sorted(n for n in by_name if n)
            raise FailedDocumentStreamNotConfigured(
                f"No SnapshotMigration named '{migration_override}' was found. "
                f"Available: {available or 'none'}."
            )
        return sm
    if not items:
        raise FailedDocumentStreamNotConfigured(
            "No SnapshotMigration resources found. Run a bulk-load workflow first."
        )
    if len(items) == 1:
        return items[0]
    names = sorted(n for n in by_name if n)
    raise FailedDocumentStreamNotConfigured(
        f"Multiple SnapshotMigration resources exist ({names}); pass --migration <name> to choose one."
    )


def load_config(migration_override: Optional[str] = None) -> FailedDocumentStreamConfig:
    return load_config_and_identity(migration_override)[0]


def load_config_and_identity(
    migration_override: Optional[str] = None,
) -> "tuple[FailedDocumentStreamConfig, MigrationIdentity]":
    """The stream's location and the backfill that wrote it, from one SnapshotMigration."""
    # Single source of truth: the failed-document-stream destination (bucket/prefix/region/endpoint) is
    # resolved by the config processor and projected onto the owning SnapshotMigration's spec, and the
    # session id is that SnapshotMigration's own UID. The console reads both directly from the
    # SnapshotMigration it is reporting on, so it always agrees with what RFS wrote. There is no
    # namespace-global "current session" ConfigMap (which could not represent parallel backfills).
    sm = _select_snapshot_migration(migration_override, _list_snapshot_migrations())
    spec = sm.get("spec", {}) or {}
    meta = sm.get("metadata", {}) or {}

    bucket = _trim(spec.get(_SPEC_BUCKET))
    if not bucket:
        raise FailedDocumentStreamNotConfigured(
            f"SnapshotMigration '{meta.get('name')}' has no failed-document-stream bucket configured "
            "(failed document stream disabled for this backfill)."
        )
    prefix = _trim(spec.get(_SPEC_PREFIX)) or "rfs-failed-document-stream/"
    if not prefix.endswith("/"):
        prefix = prefix + "/"
    session = _trim(meta.get("uid"))
    if not session:
        raise FailedDocumentStreamNotConfigured(
            f"SnapshotMigration '{meta.get('name')}' has no metadata.uid; cannot resolve the session."
        )
    cfg = FailedDocumentStreamConfig(
        bucket=bucket,
        prefix=prefix,
        session_id=session,
        region=_trim(spec.get(_SPEC_REGION)),
        endpoint=_trim(spec.get(_SPEC_ENDPOINT)),
    )
    identity = MigrationIdentity(
        name=meta.get("name") or "",
        migration_label=_trim(spec.get(_SPEC_MIGRATION_LABEL)),
        source_label=_trim(spec.get(_SPEC_SOURCE_LABEL)),
        target_label=_trim(spec.get(_SPEC_TARGET_LABEL)),
        target_endpoint=_trim(spec.get(_SPEC_TARGET_ENDPOINT)),
    )
    return cfg, identity


def _s3_client(cfg: FailedDocumentStreamConfig):
    kwargs = {}
    if cfg.region:
        kwargs["region_name"] = cfg.region
    if cfg.endpoint:
        kwargs["endpoint_url"] = cfg.endpoint
    return boto3.client("s3", **kwargs)


def location(cfg: FailedDocumentStreamConfig) -> str:
    """Return the customer-visible S3 URI for the current session's failed document stream."""
    return cfg.location_uri


def _iter_objects(cfg: FailedDocumentStreamConfig, client=None) -> Iterator[dict]:
    """Every object under the session prefix, the manifest included, so deletion removes it too."""
    client = client or _s3_client(cfg)
    paginator = client.get_paginator("list_objects_v2")
    for page in paginator.paginate(Bucket=cfg.bucket, Prefix=cfg.session_prefix):
        for obj in page.get("Contents", []) or []:
            yield obj


def _iter_object_records(client, bucket: str, key: str) -> Iterator[dict]:
    """Yield the JSON-parsed NDJSON records of one object, decompressing as we go.

    Unreadable objects and malformed lines are skipped with a warning so one bad object can't
    break inspection of the rest.
    """
    with contextlib.closing(client.get_object(Bucket=bucket, Key=key)["Body"]) as body:
        try:
            with gzip.GzipFile(fileobj=body) as gz:
                for raw_line in io.TextIOWrapper(gz, encoding="utf-8"):
                    line = raw_line.strip()
                    if not line:
                        continue
                    try:
                        yield json.loads(line)
                    except json.JSONDecodeError as e:
                        logger.warning("Skipping malformed failed document stream record in %s: %s", key, e)
        except OSError as e:
            logger.warning("Skipping unreadable failed document stream object %s: %s", key, e)


def _iter_records(cfg: FailedDocumentStreamConfig, client=None) -> Iterator[dict]:
    """Lazily yield every NDJSON record across all session objects (no sort/dedup/limit).

    Objects are listed, fetched and decompressed one at a time, so a caller that stops early
    (``has_records``) never pays for the rest of the stream, however large it is.

    The manifest lives under the same prefix and is skipped by name.
    """
    client = client or _s3_client(cfg)
    manifest = manifest_key(cfg)
    for obj in _iter_objects(cfg, client=client):
        if obj["Key"] == manifest:
            continue
        yield from _iter_object_records(client, cfg.bucket, obj["Key"])


def _dedup_key(record: dict):
    """Stable identity of a failed document: ``(targetIndex, documentId)``.

    This is invariant across re-emissions — a successor that reprocesses a partition writes the
    same (targetIndex, documentId) regardless of which checkpoint it resumed from (so it is NOT
    keyed on workItemId, which changes when the checkpoint advances). Returns ``None`` when
    documentId is absent/empty (e.g. server-generated ids): such records can't be correlated, so
    they are never collapsed.
    """
    doc_id = record.get("documentId")
    if not doc_id:
        return None
    return (record.get("targetIndex"), doc_id)


def dedupe_records(records: List[dict]) -> List[dict]:
    """Collapse duplicate failures for the same document into a single record.

    The failed document stream is at-least-once: a worker crash or a failed flush makes a successor reprocess the
    partition and re-emit the same terminal failures, so the same ``(targetIndex, documentId)``
    can appear in multiple objects. We keep the latest record per document (by timestamp).
    Records without a documentId can't be correlated and are all retained.
    """
    by_doc: dict = {}
    without_id: List[dict] = []
    for r in records:
        key = _dedup_key(r)
        if key is None:
            without_id.append(r)
            continue
        existing = by_doc.get(key)
        if existing is None or (r.get("timestamp") or "") >= (existing.get("timestamp") or ""):
            by_doc[key] = r
    return list(by_doc.values()) + without_id


def list_records(cfg: FailedDocumentStreamConfig, limit: Optional[int] = None) -> List[dict]:
    """Stream de-duplicated NDJSON records from all session objects in stable order.

    Records are de-duplicated by (targetIndex, documentId) — see ``dedupe_records`` — because the
    failed document stream is at-least-once. Stable order = (timestamp asc, documentId asc); records without a
    timestamp sort to the end. ``limit`` caps the returned list — useful in CLI contexts.
    """
    records = dedupe_records(list(_iter_records(cfg)))
    records.sort(key=lambda r: (r.get("timestamp") or "~", r.get("documentId") or ""))
    if limit is not None:
        return records[:limit]
    return records


def count(cfg: FailedDocumentStreamConfig) -> int:
    """Count distinct failed documents in this session's failed document stream.

    De-duplicates by (targetIndex, documentId) so re-emitted failures (the failed document stream is at-least-once)
    are counted once rather than inflating the total. This reads every object body, so it is O(stream size) —
    only ask for it when the number itself is wanted (``console failed-document-stream count``). Callers that
    just need to know whether the backfill had failures should use ``has_records``.
    """
    return len(dedupe_records(list(_iter_records(cfg))))


def has_records(cfg: FailedDocumentStreamConfig) -> bool:
    """Whether this session's failed document stream holds at least one failure record.

    Short-circuits on the first record, so it costs one list page plus (at most) the prefix of one
    object — unlike ``count``, it stays cheap when a backfill failed millions of documents.
    De-duplication is irrelevant here: any record means at least one distinct failed document.
    """
    return next(_iter_records(cfg), None) is not None


def delete_session(cfg: FailedDocumentStreamConfig) -> int:
    """Delete every object under the current session prefix. Returns count deleted."""
    client = _s3_client(cfg)
    deleted = 0
    batch: List[dict] = []
    for obj in _iter_objects(cfg, client=client):
        batch.append({"Key": obj["Key"]})
        if len(batch) == 1000:  # DeleteObjects max per call
            client.delete_objects(Bucket=cfg.bucket, Delete={"Objects": batch})
            deleted += len(batch)
            batch = []
    if batch:
        client.delete_objects(Bucket=cfg.bucket, Delete={"Objects": batch})
        deleted += len(batch)
    return deleted


# ##################### sealing ###################
#
# Sealing publishes a manifest of what a session holds. Only a sealed session can be redriven, since
# an open one can still gain records after enumeration. RFS seals when a backfill runs out of work;
# this covers backfills that never got there.
#
# The encoding must match SessionManifestCodec byte for byte: both may seal the same session, and
# the loser compares digests rather than overwriting.

RECORD_SUFFIX = ".ndjson.gz"
MANIFEST_FILENAME = "manifest.json"
MANIFEST_SCHEMA_VERSION = 1

_INDEX_SEGMENT = "index="
_WORKER_SEGMENT = "worker="


class SessionSealMismatch(RuntimeError):
    """An existing manifest disagrees with what is in the session now."""


def is_record_object(key: str) -> bool:
    return bool(key) and key.endswith(RECORD_SUFFIX)


def manifest_key(cfg: FailedDocumentStreamConfig) -> str:
    return cfg.session_prefix + MANIFEST_FILENAME


def _location_of(key: str):
    """The ``(index, worker)`` a record key names, or ``None`` when it names neither."""
    if not is_record_object(key):
        return None
    index = worker = None
    for segment in key.split("/"):
        if segment.startswith(_INDEX_SEGMENT):
            index = segment[len(_INDEX_SEGMENT):]
        elif segment.startswith(_WORKER_SEGMENT):
            worker = segment[len(_WORKER_SEGMENT):]
    if not index or not worker:
        return None
    return index, worker


def build_manifest(cfg: FailedDocumentStreamConfig, client=None) -> dict:
    """The manifest a live listing implies right now."""
    client = client or _s3_client(cfg)
    by_collection: dict = {}
    for obj in _iter_objects(cfg, client=client):
        location = _location_of(obj["Key"])
        if location is None:
            continue
        index, worker = location
        by_collection.setdefault(index, {}).setdefault(worker, []).append(obj["Key"])
    return {
        "schemaVersion": MANIFEST_SCHEMA_VERSION,
        "sessionId": cfg.session_id,
        "collections": [
            {
                "name": index,
                "partitions": [
                    {"name": worker, "objectKeys": sorted(keys)}
                    for worker, keys in sorted(partitions.items())
                ],
            }
            for index, partitions in sorted(by_collection.items())
        ],
    }


def canonical_manifest_bytes(manifest: dict) -> bytes:
    """Reproducible bytes for a manifest: fixed property order over already-sorted content."""
    return json.dumps(manifest, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def manifest_digest(canonical_bytes: bytes) -> str:
    return hashlib.sha256(canonical_bytes).hexdigest()


def read_manifest(cfg: FailedDocumentStreamConfig, client=None) -> Optional[dict]:
    """The session's manifest, or ``None`` when it has never been sealed."""
    client = client or _s3_client(cfg)
    try:
        body = client.get_object(Bucket=cfg.bucket, Key=manifest_key(cfg))["Body"].read()
    except ClientError as e:
        if e.response.get("Error", {}).get("Code") in ("NoSuchKey", "404", "NotFound"):
            return None
        raise
    return json.loads(body.decode("utf-8"))


def is_sealed(cfg: FailedDocumentStreamConfig, client=None) -> bool:
    return read_manifest(cfg, client=client) is not None


def seal(cfg: FailedDocumentStreamConfig, client=None) -> dict:
    """Seal, or confirm an existing seal with the same contents.

    Returns ``{"manifest", "digest", "published"}``; ``published`` says whether this call wrote it.
    Raises ``SessionSealMismatch`` when an existing manifest describes something else.
    """
    client = client or _s3_client(cfg)
    manifest = build_manifest(cfg, client=client)
    body = canonical_manifest_bytes(manifest)
    digest = manifest_digest(body)
    key = manifest_key(cfg)
    try:
        client.put_object(Bucket=cfg.bucket, Key=key, Body=body, IfNoneMatch="*")
        return {"manifest": manifest, "digest": digest, "published": True}
    except ClientError as e:
        if e.response.get("Error", {}).get("Code") not in ("PreconditionFailed", "412"):
            raise
    existing = read_manifest(cfg, client=client)
    if existing is None:
        raise RuntimeError(
            f"The manifest at {key} could not be written and could not be read back either."
        )
    existing_digest = manifest_digest(canonical_manifest_bytes(existing))
    if existing_digest != digest:
        raise SessionSealMismatch(
            f"Failure-stream session '{cfg.session_id}' is already sealed at {key} with digest "
            f"{existing_digest}, but sealing it now would produce {digest}. The session was still "
            "being written when it was first sealed. A seal is permanent; copy the objects into a "
            "new session and seal that instead."
        )
    return {"manifest": existing, "digest": existing_digest, "published": False}


def manifest_summary(manifest: dict) -> List[tuple]:
    """``(index, partitions, objects)`` per collection."""
    return [
        (
            collection.get("name", "-"),
            len(collection.get("partitions", []) or []),
            sum(len(p.get("objectKeys", []) or []) for p in collection.get("partitions", []) or []),
        )
        for collection in manifest.get("collections", []) or []
    ]


# ##################### redrive ###################
#
# A redrive is an ordinary coordinated backfill whose source is a sealed failure-stream session
# instead of a snapshot. The console resolves the session, checks it is sealed, shows what would be
# written and shapes the workflow config; RFS does the rest.

FAILED_DOCUMENT_STREAM_SOURCE_KIND = "failed-document-stream"

FAILURE_CLASSES = ("NON_RETRYABLE", "RETRYABLE_EXHAUSTED")


class RedriveConfigError(RuntimeError):
    """The saved workflow configuration cannot be turned into a redrive as asked."""


def stream_uri(cfg: FailedDocumentStreamConfig) -> str:
    """The stream root, above the ``session=`` segment, which is what the source is configured with."""
    prefix = cfg.prefix[:-1] if cfg.prefix.endswith("/") else cfg.prefix
    return f"s3://{cfg.bucket}/{prefix}" if prefix else f"s3://{cfg.bucket}"


def normalize_failure_classes(values) -> List[str]:
    """Upper-case and validate failure classes, rejecting anything unknown by name."""
    normalized = []
    for value in values or []:
        candidate = str(value).strip().upper()
        if candidate not in FAILURE_CLASSES:
            raise ValueError(
                f"Unknown failure class '{value}'. Expected one of: {', '.join(FAILURE_CLASSES)}."
            )
        if candidate not in normalized:
            normalized.append(candidate)
    return normalized


def build_source_config(
    cfg: FailedDocumentStreamConfig,
    indices: Optional[List[str]] = None,
    failure_classes: Optional[List[str]] = None,
) -> dict:
    """The ``sourceConfig`` JSON the failure-stream source parses.

    Opaque to everything but that source, which is why it is not spread across workflow options.
    """
    source_config = {
        "streamUri": stream_uri(cfg),
        "sessionId": cfg.session_id,
        "indexAllowlist": list(indices or []),
        "failureClasses": normalize_failure_classes(failure_classes),
    }
    if cfg.region:
        source_config["s3Region"] = cfg.region
    if cfg.endpoint:
        source_config["endpoint"] = cfg.endpoint
    return source_config


def plan_redrive(
    cfg: FailedDocumentStreamConfig,
    manifest: dict,
    indices: Optional[List[str]] = None,
    failure_classes: Optional[List[str]] = None,
    limit: Optional[int] = None,
    client=None,
) -> dict:
    """What a redrive would write: ``indices``, ``documents`` (sampled to ``limit``), ``total`` and
    ``skipped_without_id``.

    Reads every record in scope, which is the only way to answer what is about to be overwritten.
    """
    wanted_indices = set(indices or [])
    wanted_classes = set(normalize_failure_classes(failure_classes))
    keys = []
    for collection in manifest.get("collections", []) or []:
        if wanted_indices and collection.get("name") not in wanted_indices:
            continue
        for partition in collection.get("partitions", []) or []:
            keys.extend(partition.get("objectKeys", []) or [])

    client = client or _s3_client(cfg)
    per_index: dict = {}
    sample: List[dict] = []
    total = 0
    skipped_without_id = 0
    for key in sorted(keys):
        for record in _iter_object_records(client, cfg.bucket, key):
            if wanted_classes and record.get("failureClass") not in wanted_classes:
                continue
            total += 1
            index = record.get("targetIndex") or "(unknown)"
            per_index[index] = per_index.get(index, 0) + 1
            if not record.get("documentId"):
                skipped_without_id += 1
            if limit is None or len(sample) < limit:
                sample.append(record)
    return {
        "indices": per_index,
        "documents": sample,
        "total": total,
        "skipped_without_id": skipped_without_id,
    }


def _document_backfill_entries(data: dict) -> List[tuple]:
    """Every ``(source_label, target_label, migration_label, entry)`` a workflow config declares."""
    entries = []
    for migration in data.get("snapshotMigrationConfigs", []) or []:
        source_label = migration.get("fromSource")
        target_label = migration.get("toTarget")
        per_snapshot = migration.get("perSnapshotConfig", {}) or {}
        for snapshot_name, configs in per_snapshot.items():
            for position, entry in enumerate(configs or []):
                if not isinstance(entry, dict) or "documentBackfillConfig" not in entry:
                    continue
                label = entry.get("label") or f"{snapshot_name}[{position}]"
                entries.append((source_label, target_label, label, entry))
    return entries


def describe_backfill_entries(data: dict) -> List[str]:
    """Human-readable names for the backfills a config declares, for error messages."""
    return [f"{source}/{target}/{label}"
            for (source, target, label, _entry) in _document_backfill_entries(data)]


def find_backfill_entry(data: dict, identity: MigrationIdentity) -> dict:
    """The one ``documentBackfillConfig`` that produced ``identity``'s session.

    Matched on the triple the config processor projected onto the SnapshotMigration, with the
    target's endpoint checked too, so an edited configuration fails here rather than redriving
    documents somewhere they were never destined for.
    """
    entries = _document_backfill_entries(data)
    if not entries:
        raise RedriveConfigError(
            "The saved workflow configuration declares no document backfill, so it cannot be the "
            f"one that produced {identity.describe()}. Point --config-session at the configuration "
            "that ran this migration."
        )
    wanted = (identity.source_label, identity.target_label, identity.migration_label)
    matching = [entry for (source, target, label, entry) in entries
                if (source, target, label) == wanted]
    if not matching:
        available = ", ".join(describe_backfill_entries(data)) or "none"
        raise RedriveConfigError(
            f"The saved workflow configuration declares no backfill matching {identity.describe()} "
            f"— looked for '{identity.source_label}/{identity.target_label}/"
            f"{identity.migration_label}', found: {available}. A redrive reuses the target and "
            "transforms of the run that produced the failures, so it needs that run's own "
            "configuration; point --config-session at it."
        )
    if len(matching) > 1:
        raise RedriveConfigError(
            f"The saved workflow configuration declares {len(matching)} backfills matching "
            f"{identity.describe()}, so which one produced the failures is ambiguous."
        )

    configured_endpoint = _configured_target_endpoint(data, identity.target_label)
    endpoints_disagree = (identity.target_endpoint and configured_endpoint and
                          configured_endpoint != identity.target_endpoint)
    if endpoints_disagree:
        raise RedriveConfigError(
            f"Target '{identity.target_label}' in the saved workflow configuration points at "
            f"{configured_endpoint}, but {identity.describe()} wrote to "
            f"{identity.target_endpoint}. The configuration has changed since that run; redriving "
            "would send these documents to a different cluster."
        )
    return matching[0]


def _configured_target_endpoint(data: dict, target_label: Optional[str]) -> Optional[str]:
    target = (data.get("targetClusters", {}) or {}).get(target_label) or {}
    return _trim(target.get("endpoint")) if isinstance(target, dict) else None


def build_redrive_config(data: dict, source_config: dict, identity: MigrationIdentity,
                         allow_missing_document_ids: bool = False) -> dict:
    """A copy of the workflow config whose document backfill reads the failure stream.

    Everything else is left alone, so the redrive runs against the same target, transforms and
    tuning as the run that produced the failures. Which backfill to modify is derived from
    ``identity`` rather than asked for.
    """
    redrive = copy.deepcopy(data)
    entry = find_backfill_entry(redrive, identity)
    backfill = entry.get("documentBackfillConfig")
    if not isinstance(backfill, dict):
        # `documentBackfillConfig:` with no value parses as None.
        backfill = {}
        entry["documentBackfillConfig"] = backfill
    backfill["sourceKind"] = FAILED_DOCUMENT_STREAM_SOURCE_KIND
    backfill["sourceConfig"] = json.dumps(source_config, separators=(",", ":"))
    if allow_missing_document_ids:
        backfill["allowMissingDocumentIds"] = True
    return redrive

"""Workflow-facing operations for the RFS Reindex-from-Snapshot failed document stream.

The failed document stream is an append-only set of NDJSON.gz objects in S3, written by RFS workers
when terminal document failures occur. Records for a given
backfill session live under ``s3://<bucket>/<prefix>/session=<session_id>/`` —
new runs use a new ``session_id``, so prior-run records are never mixed in.

Bucket/region/endpoint/prefix/session are read from a single source of truth: the
``rfs-failed-document-stream-current-session`` Kubernetes ConfigMap. The config processor resolves
the effective bucket/region/endpoint (including the deployment default) before workflow submission and
the bulk-load workflow publishes them — along with the current ``{{workflow.uid}}`` session id — to
this ConfigMap before launching RFS. The console reads it via the Kubernetes API on demand, so it
always reports exactly what RFS wrote, with no separate env-based resolution that could drift.

``--session <id>`` overrides only the session id, to inspect a specific (e.g. historical) run within
the same deployment.

This module is intentionally a thin wrapper around the S3 listing/get APIs so a
customer can also inspect the failed document stream with the aws CLI if they prefer.
"""
from __future__ import annotations

import gzip
import io
import json
import logging
from dataclasses import dataclass
from typing import Iterator, List, Optional

import boto3
from botocore.exceptions import BotoCoreError, ClientError

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


FAILED_DOCUMENT_STREAM_SESSION_CONFIGMAP_NAME = "rfs-failed-document-stream-current-session"

_configmap_cache: Optional[dict] = None


def _read_configmap(key: str) -> Optional[str]:
    """Read a key from the rfs-failed-document-stream-current-session ConfigMap via the Kubernetes API."""
    global _configmap_cache
    if _configmap_cache is None:
        _configmap_cache = _fetch_configmap_data()
    value = _configmap_cache.get(key)
    return value.strip() if value else None


def _fetch_configmap_data() -> dict:
    try:
        from kubernetes import client, config
        from kubernetes.client.rest import ApiException
        try:
            config.load_incluster_config()
        except config.ConfigException:
            config.load_kube_config()
        v1 = client.CoreV1Api()
        from console_link.workflow.models.utils import get_current_namespace
        ns = get_current_namespace()
        cm = v1.read_namespaced_config_map(name=FAILED_DOCUMENT_STREAM_SESSION_CONFIGMAP_NAME, namespace=ns)
        return cm.data or {}
    except ImportError:
        logger.debug("kubernetes client not available; ConfigMap lookup skipped")
        return {}
    except ApiException as e:
        if e.status == 404:
            logger.debug("ConfigMap %s not found (no bulk-load run yet)", FAILED_DOCUMENT_STREAM_SESSION_CONFIGMAP_NAME)
        else:
            logger.warning("Failed to read ConfigMap %s: %s", FAILED_DOCUMENT_STREAM_SESSION_CONFIGMAP_NAME, e)
        return {}
    except Exception as e:
        logger.warning("Failed to read ConfigMap %s: %s", FAILED_DOCUMENT_STREAM_SESSION_CONFIGMAP_NAME, e)
        return {}


def load_config(session_override: Optional[str] = None) -> FailedDocumentStreamConfig:
    global _configmap_cache
    _configmap_cache = None
    # Single source of truth: the config processor resolves the effective bucket/region/endpoint/prefix
    # before workflow submission and the bulk-load workflow publishes them to the
    # rfs-failed-document-stream-current-session ConfigMap. The console reads those resolved values so it
    # always agrees with what RFS wrote, rather than re-resolving from console env (which could drift).
    bucket = _read_configmap("bucket")
    if not bucket:
        raise FailedDocumentStreamNotConfigured(
            "No failed document stream bucket is configured. Run a bulk-load workflow first, which "
            "publishes the resolved bucket to the rfs-failed-document-stream-current-session ConfigMap."
        )
    prefix = _read_configmap("prefix") or "rfs-failed-document-stream/"
    if not prefix.endswith("/"):
        prefix = prefix + "/"
    # The ConfigMap holds the *current* session id (Argo workflow UID) the bulk-load workflow most
    # recently published. --session targets a specific (e.g. historical) run instead.
    session = session_override or _read_configmap("session_id")
    if not session:
        raise FailedDocumentStreamNotConfigured(
            "No failed document stream session id is available. Run a bulk-load workflow first "
            "(which publishes the workflow UID to the rfs-failed-document-stream-current-session ConfigMap), "
            "or pass --session <id> to target a specific historical run."
        )
    region = _read_configmap("region")
    endpoint = _read_configmap("endpoint")
    return FailedDocumentStreamConfig(
        bucket=bucket, prefix=prefix, session_id=session, region=region, endpoint=endpoint
    )


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
    client = client or _s3_client(cfg)
    paginator = client.get_paginator("list_objects_v2")
    for page in paginator.paginate(Bucket=cfg.bucket, Prefix=cfg.session_prefix):
        for obj in page.get("Contents", []) or []:
            yield obj


def _read_all_records(cfg: FailedDocumentStreamConfig, client=None) -> List[dict]:
    """Read and JSON-parse every NDJSON record across all session objects (no sort/dedup/limit).

    Skips non-gzip objects and malformed lines with a warning so a single bad object can't
    break inspection of the rest.
    """
    client = client or _s3_client(cfg)
    records: List[dict] = []
    for obj in _iter_objects(cfg, client=client):
        body = client.get_object(Bucket=cfg.bucket, Key=obj["Key"])["Body"].read()
        try:
            decoded = gzip.GzipFile(fileobj=io.BytesIO(body)).read().decode("utf-8")
        except OSError as e:
            logger.warning("Skipping non-gzip failed document stream object %s: %s", obj["Key"], e)
            continue
        for line in decoded.splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError as e:
                logger.warning("Skipping malformed failed document stream record in %s: %s", obj["Key"], e)
    return records


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
    records = dedupe_records(_read_all_records(cfg))
    records.sort(key=lambda r: (r.get("timestamp") or "~", r.get("documentId") or ""))
    if limit is not None:
        return records[:limit]
    return records


def count(cfg: FailedDocumentStreamConfig) -> int:
    """Count distinct failed documents in this session's failed document stream.

    De-duplicates by (targetIndex, documentId) so re-emitted failures (the failed document stream is at-least-once)
    are counted once rather than inflating the total. For very large failed document streams we read object bodies —
    workflows typically expect a small number of failures, so this favors accuracy over a cheap
    object/line-count approximation.
    """
    return len(dedupe_records(_read_all_records(cfg)))


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


def safe_count(cfg: FailedDocumentStreamConfig) -> Optional[int]:
    """Like ``count`` but swallows S3 errors so a status command never breaks
    on a misconfigured failed document stream. Returns ``None`` if the count is unavailable."""
    try:
        return count(cfg)
    except (BotoCoreError, ClientError) as e:
        logger.warning("Failed to count failed document stream records: %s", e)
        return None

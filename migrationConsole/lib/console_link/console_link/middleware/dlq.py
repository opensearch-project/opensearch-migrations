"""Workflow-facing operations for the RFS Reindex-from-Snapshot DLQ.

The DLQ is an append-only set of NDJSON.gz objects in S3, written by RFS workers
when terminal document failures occur (see issue #2975). Records for a given
backfill session live under ``s3://<bucket>/<prefix>/session=<session_id>/`` —
new runs use a new ``session_id``, so prior-run records are never mixed in.

Location/session are conveyed to the console via two paths, in priority order:

1. **CLI / env override** — for explicit operator control:
   * ``RFS_DLQ_S3_BUCKET`` — explicit bucket override
   * ``RFS_DLQ_S3_PREFIX`` — key prefix above ``session=``
   * ``RFS_DLQ_SESSION_ID`` — pinned session id
   * ``RFS_DLQ_S3_REGION`` — region for the bucket

2. **Per-run ConfigMap mount** at :data:`SESSION_CONFIGMAP_MOUNT` — the
   bulk-load workflow patches a ``rfs-dlq-current-session`` ConfigMap with the
   current ``{{workflow.uid}}`` before launching RFS, and the console mounts
   that ConfigMap as a volume. Each new workflow rotates the values, so the
   console always sees the *latest* run's session without a pod restart.

3. **Default bucket fallback** — ``MIGRATIONS_DEFAULT_S3_BUCKET`` /
   ``BUCKET_NAME`` from the deployment-provisioned
   ``migrations-default-<account>-<stage>-<region>`` bucket.

This module is intentionally a thin wrapper around the S3 listing/get APIs so a
customer can also inspect the DLQ with the aws CLI if they prefer.
"""
from __future__ import annotations

import gzip
import io
import json
import logging
import os
from dataclasses import dataclass
from typing import Iterator, List, Optional

import boto3
from botocore.exceptions import BotoCoreError, ClientError

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class DlqConfig:
    bucket: str
    prefix: str          # always trailing-slash-terminated
    session_id: str
    region: Optional[str] = None

    @property
    def session_prefix(self) -> str:
        return f"{self.prefix}session={self.session_id}/"

    @property
    def location_uri(self) -> str:
        return f"s3://{self.bucket}/{self.session_prefix}"


class DlqNotConfigured(RuntimeError):
    """Raised when no session id / bucket is available from any source."""


SESSION_CONFIGMAP_MOUNT = "/etc/rfs-dlq"


def _read_mounted(key: str) -> Optional[str]:
    # ConfigMap-as-volume mounts expose each data key as a regular file.
    # kubelet refreshes the symlinks within ~60s after the ConfigMap is
    # patched, so re-running ``console backfill dlq …`` after a new
    # workflow starts will pick up the new session without a pod restart.
    path = os.path.join(SESSION_CONFIGMAP_MOUNT, key)
    try:
        with open(path, "r", encoding="utf-8") as f:
            value = f.read().strip()
            return value or None
    except (FileNotFoundError, IsADirectoryError, PermissionError):
        return None


def load_config(session_override: Optional[str] = None) -> DlqConfig:
    # Bucket resolution: explicit override → mounted ConfigMap → deployment
    # default bucket. The explicit override exists so an operator can pin
    # inspection to a non-default bucket (e.g., investigating a historical
    # run that wrote elsewhere).
    bucket = (
        os.environ.get("RFS_DLQ_S3_BUCKET")
        or _read_mounted("bucket")
        or os.environ.get("MIGRATIONS_DEFAULT_S3_BUCKET")
        or os.environ.get("BUCKET_NAME")
    )
    if not bucket:
        raise DlqNotConfigured(
            "No DLQ bucket is configured. Run a bulk-load workflow first (which "
            "patches /etc/rfs-dlq/bucket), or set RFS_DLQ_S3_BUCKET / "
            "MIGRATIONS_DEFAULT_S3_BUCKET / BUCKET_NAME in the console env."
        )
    # Prefix resolution: env override → mounted ConfigMap → safe default.
    prefix = (
        os.environ.get("RFS_DLQ_S3_PREFIX")
        or _read_mounted("prefix")
        or "rfs-dlq/"
    )
    if not prefix.endswith("/"):
        prefix = prefix + "/"
    # Session resolution: --session arg → env override → mounted ConfigMap.
    # The mounted file is the *current* session id (Argo workflow UID) that
    # the bulk-load workflow most recently patched in.
    session = (
        session_override
        or os.environ.get("RFS_DLQ_SESSION_ID")
        or _read_mounted("session_id")
    )
    if not session:
        raise DlqNotConfigured(
            "No DLQ session id is available. Run a bulk-load workflow first "
            "(which patches /etc/rfs-dlq/session_id with the workflow UID), "
            "or pass --session <id> to target a specific historical run."
        )
    region = os.environ.get("RFS_DLQ_S3_REGION") or _read_mounted("region")
    return DlqConfig(bucket=bucket, prefix=prefix, session_id=session, region=region)


def _s3_client(cfg: DlqConfig):
    if cfg.region:
        return boto3.client("s3", region_name=cfg.region)
    return boto3.client("s3")


def location(cfg: DlqConfig) -> str:
    """Return the customer-visible S3 URI for the current session's DLQ."""
    return cfg.location_uri


def _iter_objects(cfg: DlqConfig, client=None) -> Iterator[dict]:
    client = client or _s3_client(cfg)
    paginator = client.get_paginator("list_objects_v2")
    for page in paginator.paginate(Bucket=cfg.bucket, Prefix=cfg.session_prefix):
        for obj in page.get("Contents", []) or []:
            yield obj


def list_records(cfg: DlqConfig, limit: Optional[int] = None) -> List[dict]:
    """Stream NDJSON records from all session objects in stable order.

    Stable order = (timestamp asc, documentId asc); records without a timestamp
    sort to the end. ``limit`` caps the returned list — useful in CLI contexts.
    """
    client = _s3_client(cfg)
    records: List[dict] = []
    for obj in _iter_objects(cfg, client=client):
        body = client.get_object(Bucket=cfg.bucket, Key=obj["Key"])["Body"].read()
        try:
            decoded = gzip.GzipFile(fileobj=io.BytesIO(body)).read().decode("utf-8")
        except OSError as e:
            logger.warning("Skipping non-gzip DLQ object %s: %s", obj["Key"], e)
            continue
        for line in decoded.splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError as e:
                logger.warning("Skipping malformed DLQ record in %s: %s", obj["Key"], e)
    records.sort(key=lambda r: (r.get("timestamp") or "~", r.get("documentId") or ""))
    if limit is not None:
        return records[:limit]
    return records


def count(cfg: DlqConfig) -> int:
    """Count failed document records in this session's DLQ.

    Counts NDJSON lines across all session objects. For very large DLQs we read
    object bodies — workflows typically expect a small number of failures, so
    this trade-off favors accuracy over a cheap object-count approximation.
    """
    client = _s3_client(cfg)
    total = 0
    for obj in _iter_objects(cfg, client=client):
        body = client.get_object(Bucket=cfg.bucket, Key=obj["Key"])["Body"].read()
        try:
            decoded = gzip.GzipFile(fileobj=io.BytesIO(body)).read().decode("utf-8")
        except OSError:
            continue
        total += sum(1 for line in decoded.splitlines() if line.strip())
    return total


def delete_session(cfg: DlqConfig) -> int:
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


def safe_count(cfg: DlqConfig) -> Optional[int]:
    """Like ``count`` but swallows S3 errors so a status command never breaks
    on a misconfigured DLQ. Returns ``None`` if the count is unavailable."""
    try:
        return count(cfg)
    except (BotoCoreError, ClientError) as e:
        logger.warning("Failed to count DLQ records: %s", e)
        return None

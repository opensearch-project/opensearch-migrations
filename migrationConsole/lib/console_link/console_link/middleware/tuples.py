"""Reading the traffic replayer's result tuples.

The replayer writes one tuple per replayed request -- the source request and response next to the
target's -- as gzipped JSON lines in S3, under the bucket and prefix of its ``TrafficReplay`` spec
(``tupleS3Bucket`` / ``tupleS3Prefix``). Each replayer pod writes under
``<prefix><pod name>_<uuid>/<yyyy>/<mm>/<dd>/<hh>/``, and pod names start with the
``TrafficReplay`` name, so a replayer's objects are the keys under its own name.

Tuples are read from the migration console's read-only mount of the deployment's default bucket
(``/artifacts``, mounted by start-console.sh) when the replayer writes there, and through the S3 API
otherwise. Bodies are already decoded by the replayer (``inlinedJsonBody``, ``inlinedTextBody``,
...); only ``inlinedBinaryBody`` arrives base64-encoded, and it is decoded when it turns out to be
UTF-8 text.
"""
from __future__ import annotations

import base64
import binascii
import datetime as dt
import gzip
import io
import json
import logging
import os
import re
from dataclasses import dataclass
from typing import IO, Iterable, Iterator, List, Optional

logger = logging.getLogger(__name__)

TRAFFIC_REPLAY_API_GROUP = "migrations.opensearch.org"
TRAFFIC_REPLAY_API_VERSION = "v1alpha1"
TRAFFIC_REPLAY_PLURAL = "trafficreplays"

ARTIFACTS_MOUNT = "/artifacts"
BINARY_BODY_KEY = "inlinedBinaryBody"
TEXT_BODY_KEY = "inlinedTextBody"


class TupleSelectionError(RuntimeError):
    """The replayer to read tuples for couldn't be chosen, or it writes no tuples."""


@dataclass(frozen=True)
class TupleLocation:
    replayer: str
    bucket: str
    prefix: str
    region: Optional[str] = None
    endpoint: Optional[str] = None

    def describe(self) -> str:
        return f"s3://{self.bucket}/{self.prefix}{self.replayer}-*"


@dataclass(frozen=True)
class TupleObject:
    key: str
    last_modified: dt.datetime


def _trim(value) -> Optional[str]:
    return value.strip() if isinstance(value, str) and value.strip() else None


def list_traffic_replays() -> List[dict]:
    """TrafficReplay resources in the console's namespace ([] when the k8s client is unavailable)."""
    try:
        from kubernetes import client, config
        from kubernetes.client.rest import ApiException
    except ImportError:
        logger.debug("kubernetes client not available; TrafficReplay lookup skipped")
        return []
    try:
        try:
            config.load_incluster_config()
        except config.ConfigException:
            config.load_kube_config()
        from console_link.workflow.models.utils import get_current_namespace
        resp = client.CustomObjectsApi().list_namespaced_custom_object(
            group=TRAFFIC_REPLAY_API_GROUP, version=TRAFFIC_REPLAY_API_VERSION,
            namespace=get_current_namespace(), plural=TRAFFIC_REPLAY_PLURAL)
        return resp.get("items", []) or []
    except ApiException as e:
        logger.warning("Failed to list TrafficReplay resources: %s", e)
        return []


def select_traffic_replay(items: List[dict], replayer: Optional[str] = None, source: Optional[str] = None,
                          target: Optional[str] = None) -> dict:
    """Choose one TrafficReplay by name or source/target label; the only one needs no selector."""
    candidates = [
        item for item in items
        if (not replayer or item.get("metadata", {}).get("name") == replayer) and
        (not source or item.get("spec", {}).get("sourceLabel") == source) and
        (not target or item.get("spec", {}).get("targetLabel") == target)
    ]
    names = sorted(item.get("metadata", {}).get("name", "") for item in items)
    if not candidates:
        if not items:
            raise TupleSelectionError("No traffic replayers are deployed.")
        raise TupleSelectionError(f"No traffic replayer matches the selection. Replayers: {', '.join(names)}.")
    if len(candidates) > 1:
        raise TupleSelectionError(
            "More than one traffic replayer matches; choose one with --replayer, --source or --target: " +
            ", ".join(sorted(c["metadata"]["name"] for c in candidates)) + ".")
    return candidates[0]


def tuple_location(traffic_replay: dict) -> TupleLocation:
    name = traffic_replay.get("metadata", {}).get("name", "")
    spec = traffic_replay.get("spec", {})
    bucket = _trim(spec.get("tupleS3Bucket"))
    if not bucket:
        raise TupleSelectionError(
            f"Traffic replayer {name} doesn't write tuples to S3 (its config sets no tupleS3Bucket).")
    prefix = _trim(spec.get("tupleS3Prefix")) or ""
    if prefix and not prefix.endswith("/"):
        prefix += "/"
    return TupleLocation(replayer=name, bucket=bucket, prefix=prefix,
                         region=_trim(spec.get("tupleS3Region")), endpoint=_trim(spec.get("tupleS3Endpoint")))


def _belongs_to_replayer(key: str, loc: TupleLocation) -> bool:
    # <prefix><replayer>-<replicaset hash>-<pod suffix>_<uuid>/...; the pattern keeps a replayer named
    # "a" from matching objects written by one named "a-b".
    rest = key[len(loc.prefix):] if key.startswith(loc.prefix) else None
    return rest is not None and re.match(re.escape(loc.replayer) + r"-[a-z0-9]+-[a-z0-9]+_", rest) is not None


def _mounted_root(loc: TupleLocation, mount: str = ARTIFACTS_MOUNT) -> Optional[str]:
    """The local directory holding the location's bucket, when the console mounts it."""
    if os.environ.get("REPO_ARTIFACTS_BUCKET", "").rstrip("/") != f"s3://{loc.bucket}":
        return None
    return mount if os.path.isdir(os.path.join(mount, loc.prefix)) else None


class _MountedSource:
    def __init__(self, root: str, loc: TupleLocation):
        self.root, self.loc = root, loc

    def objects(self) -> List[TupleObject]:
        found = []
        base = os.path.join(self.root, self.loc.prefix)
        for entry in os.listdir(base):
            if not _belongs_to_replayer(self.loc.prefix + entry + "/", self.loc):
                continue
            for dirpath, _dirs, files in os.walk(os.path.join(base, entry)):
                for f in files:
                    path = os.path.join(dirpath, f)
                    found.append(TupleObject(
                        key=os.path.relpath(path, self.root),
                        last_modified=dt.datetime.fromtimestamp(os.path.getmtime(path), dt.timezone.utc)))
        return found

    def open(self, obj: TupleObject) -> IO[bytes]:
        return open(os.path.join(self.root, obj.key), "rb")


class _S3Source:
    def __init__(self, loc: TupleLocation, client=None):
        self.loc = loc
        if client is None:
            import boto3
            kwargs = {}
            if loc.region:
                kwargs["region_name"] = loc.region
            if loc.endpoint:
                kwargs["endpoint_url"] = loc.endpoint
            client = boto3.client("s3", **kwargs)
        self.client = client

    def objects(self) -> List[TupleObject]:
        found = []
        paginator = self.client.get_paginator("list_objects_v2")
        for page in paginator.paginate(Bucket=self.loc.bucket, Prefix=self.loc.prefix + self.loc.replayer + "-"):
            for item in page.get("Contents", []) or []:
                if _belongs_to_replayer(item["Key"], self.loc):
                    found.append(TupleObject(key=item["Key"], last_modified=item["LastModified"]))
        return found

    def open(self, obj: TupleObject) -> IO[bytes]:
        return io.BytesIO(self.client.get_object(Bucket=self.loc.bucket, Key=obj.key)["Body"].read())


def tuple_source(loc: TupleLocation, s3_client=None, mount: str = ARTIFACTS_MOUNT):
    root = None if s3_client is not None else _mounted_root(loc, mount)
    return _MountedSource(root, loc) if root else _S3Source(loc, s3_client)


def _lines(stream: IO[bytes]) -> Iterator[str]:
    head = stream.read(2)
    stream.seek(0)
    text = io.TextIOWrapper(gzip.GzipFile(fileobj=stream) if head == b"\x1f\x8b" else stream, encoding="utf-8")
    for line in text:
        if line.strip():
            yield line


def _decode_binary_bodies(node):
    """Replace base64 binary bodies that are UTF-8 text with inlinedTextBody, recursively."""
    if isinstance(node, dict):
        if isinstance(node.get(BINARY_BODY_KEY), str):
            try:
                node[TEXT_BODY_KEY] = base64.b64decode(node[BINARY_BODY_KEY], validate=True).decode("utf-8")
                del node[BINARY_BODY_KEY]
            except (binascii.Error, UnicodeDecodeError):
                pass
        for value in node.values():
            _decode_binary_bodies(value)
    elif isinstance(node, list):
        for value in node:
            _decode_binary_bodies(value)
    return node


def parse_tuple(line: str) -> dict:
    return _decode_binary_bodies(json.loads(line))


def read_tuples(stream: IO[bytes]) -> Iterator[dict]:
    """Tuples from a tuple file (gzipped or plain JSON lines)."""
    for line in _lines(stream):
        yield parse_tuple(line)


def newest_tuples(source, limit: Optional[int] = None, since: Optional[dt.datetime] = None) -> List[dict]:
    """The newest tuples (at most `limit`, from objects modified after `since`), oldest first."""
    objects = sorted(source.objects(), key=lambda o: o.last_modified, reverse=True)
    if since is not None:
        objects = [o for o in objects if o.last_modified >= since]
    collected: List[List[dict]] = []
    count = 0
    for obj in objects:
        with source.open(obj) as stream:
            batch = list(read_tuples(stream))
        if limit is not None and count + len(batch) > limit:
            batch = batch[len(batch) - (limit - count):]
        collected.append(batch)
        count += len(batch)
        if limit is not None and count >= limit:
            break
    return [t for batch in reversed(collected) for t in batch]


def write_tuples(tuples: Iterable[dict], out: IO[str], pretty: bool = False) -> int:
    n = 0
    for t in tuples:
        out.write(json.dumps(t, indent=2 if pretty else None) + "\n")
        n += 1
    return n


_DURATION = re.compile(r"^(\d+)([smhd])$")


def parse_since(value: Optional[str], now: Optional[dt.datetime] = None) -> Optional[dt.datetime]:
    """'15m', '2h', '1d' -> the UTC time that long ago."""
    if not value:
        return None
    m = _DURATION.match(value.strip())
    if not m:
        raise ValueError(f"Invalid duration {value!r}; use a number followed by s, m, h or d (e.g. 15m).")
    seconds = int(m.group(1)) * {"s": 1, "m": 60, "h": 3600, "d": 86400}[m.group(2)]
    return (now or dt.datetime.now(dt.timezone.utc)) - dt.timedelta(seconds=seconds)

"""S3-side plumbing for the live replay-quality TUI — alternative to Kafka streaming.

Mirrors 06-live-jaccard.sh's approach: TrafficReplayer also writes every tuple as
gzip-compressed NDJSON to S3 (RotatingGzipS3ObjectWriter), rotating files by age/size/count.
This reader polls that prefix for new (never-yet-seen) .log.gz files and yields their
tuples — same tuple JSON schema as the Kafka topic, so scoring.py needs no changes to
consume either source.

Inherently poll-based, not push — expect real, rotation-interval-scale lag versus Kafka's
near-real-time delivery (the README notes ~10s for the older bash script this mirrors).
Runs everything via `kubectl exec` into migration-console-0, same rationale as
kafka_reader.py: no in-cluster AWS CLI/S3 client on the operator's own machine, but the pod
already has the `aws` CLI and LocalStack credentials baked in.
"""
import gzip
import json
import logging
import subprocess
import threading
from typing import Iterator, List, Optional, Set

logger = logging.getLogger(__name__)


class S3ReaderError(Exception):
    pass


def _kubectl(*args: str, input_bytes: Optional[bytes] = None, timeout: Optional[float] = None) -> bytes:
    proc = subprocess.run(
        ["kubectl", *args], input=input_bytes, capture_output=True, timeout=timeout,
    )
    if proc.returncode != 0:
        raise S3ReaderError(
            f"kubectl {' '.join(args)} failed: {proc.stderr.decode(errors='replace').strip()}")
    return proc.stdout


class S3TupleReader:
    """Polls a LocalStack/S3 prefix for new TrafficReplayer tuple files, yielding decoded
    tuples in file order. Starts from whatever already exists at ensure_ready() time —
    like KafkaTupleReader consuming from the latest offset, not --from-beginning — so a
    long-running demo's full history is never replayed on startup."""

    def __init__(self, namespace: str, *,
                 pod: str = "migration-console-0",
                 bucket: str = "migrations-default-123456789012-dev-us-east-2",
                 prefix: str = "tuples/",
                 endpoint_url: str = "http://localstack:4566",
                 region: str = "us-east-2",
                 poll_interval: float = 10.0):
        self.namespace = namespace
        self.pod = pod
        self.bucket = bucket
        self.prefix = prefix
        self.endpoint_url = endpoint_url
        self.region = region
        self.poll_interval = poll_interval
        self.errors = 0
        self._seen_keys: Set[str] = set()
        self._stop_event = threading.Event()

    # --- One-time setup ---

    def ensure_ready(self) -> None:
        """Verify the pod exists and establish the starting cursor — every key that already
        exists is marked seen so stream() only yields tuples from files created after this
        point."""
        self._check_pod()
        try:
            self._seen_keys = set(self._list_log_keys())
        except S3ReaderError:
            # Bucket/prefix may not exist yet on a fresh demo — fine, stream() will just
            # pick up files as the replayer creates them.
            self._seen_keys = set()

    def _check_pod(self) -> None:
        proc = subprocess.run(
            ["kubectl", "get", "pod", self.pod, "-n", self.namespace],
            capture_output=True)
        if proc.returncode != 0:
            raise S3ReaderError(f"pod '{self.pod}' not found in namespace '{self.namespace}'")

    # --- S3 access (all via the migration-console pod's own aws CLI + LocalStack creds) ---

    def _aws_cmd(self, *args: str) -> List[str]:
        return [
            "exec", "-n", self.namespace, self.pod, "--",
            "env", "AWS_ACCESS_KEY_ID=test", "AWS_SECRET_ACCESS_KEY=test",
            "aws", "--endpoint-url", self.endpoint_url, "--region", self.region,
            "s3", *args,
        ]

    def _list_log_keys(self) -> List[str]:
        """Every tuples/<replayerId>/.../*.log.gz key currently in the bucket, across all
        replayer prefixes — a replayer restart gets a fresh prefix (RotatingGzipS3ObjectWriter's
        key format embeds the pod name), and old prefixes are never cleaned up, so all of
        them must be watched, not just the newest."""
        out = _kubectl(*self._aws_cmd("ls", f"s3://{self.bucket}/{self.prefix}")).decode(errors="replace")
        replayer_prefixes = []
        for line in out.splitlines():
            part = line.strip().rstrip("/")
            if part:
                replayer_prefixes.append(part.split()[-1])

        keys: List[str] = []
        for replayer in replayer_prefixes:
            out = _kubectl(*self._aws_cmd(
                "ls", "--recursive", f"s3://{self.bucket}/{self.prefix}{replayer}")).decode(errors="replace")
            for line in out.splitlines():
                parts = line.split()
                if parts and parts[-1].endswith(".log.gz"):
                    keys.append(parts[-1])
        return keys

    def _gunzip_lines(self, key: str) -> Iterator[str]:
        raw = _kubectl(*self._aws_cmd("cp", f"s3://{self.bucket}/{key}", "-"))
        try:
            text = gzip.decompress(raw).decode("utf-8", errors="replace")
        except OSError as e:
            raise S3ReaderError(f"failed to gunzip {key}: {e}")
        for line in text.splitlines():
            line = line.strip()
            if line:
                yield line

    # --- Streaming ---

    def _decode_tuple(self, key: str, line: str) -> Optional[dict]:
        try:
            return json.loads(line)
        except Exception:
            self.errors += 1
            logger.debug("Failed to decode tuple line from %s: %r", key, line[:200])
            return None

    def _tuples_from_key(self, key: str) -> Iterator[dict]:
        """Yield one object's tuples. A file that can't be read, or a line that can't be
        decoded, is counted in self.errors and skipped rather than ending the stream."""
        try:
            for line in self._gunzip_lines(key):
                tup = self._decode_tuple(key, line)
                if tup is not None:
                    yield tup
        except S3ReaderError:
            self.errors += 1
            logger.debug("Failed to read %s", key)

    def stream(self) -> Iterator[dict]:
        """Poll for new .log.gz files and yield their tuples in key (chronological) order.
        Runs until stop() is called."""
        while not self._stop_event.is_set():
            try:
                all_keys = sorted(self._list_log_keys())
            except S3ReaderError:
                all_keys = []
            new_keys = [k for k in all_keys if k not in self._seen_keys]
            for key in new_keys:
                self._seen_keys.add(key)
                yield from self._tuples_from_key(key)
                if self._stop_event.is_set():
                    return
            self._stop_event.wait(self.poll_interval)

    def stop(self) -> None:
        self._stop_event.set()

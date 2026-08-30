"""Structured runtime status for resources shown by workflow manage."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import datetime, timezone
import os
import signal
import subprocess
import threading
import time
from typing import Any, Callable, Dict, Mapping, Optional, Sequence, Tuple

from kubernetes.client.rest import ApiException


CRD_GROUP = "migrations.opensearch.org"
CRD_VERSION = "v1alpha1"
DEFAULT_COMMAND_TIMEOUT_SECONDS = 20
DEFAULT_CACHE_SECONDS = 10
MAX_DETAIL_LINES = 60
MAX_DETAIL_CHARACTERS = 12_000


class RuntimeStatusUnavailable(RuntimeError):
    """The requested resource cannot provide runtime status."""


@dataclass(frozen=True)
class ConsoleCommandResult:
    success: bool
    output: str
    error: Optional[str] = None


@dataclass(frozen=True)
class RuntimeStatusSection:
    key: str
    title: str
    state: str
    summary: str
    source: str
    details: Tuple[str, ...] = ()


@dataclass(frozen=True)
class RuntimeStatus:
    node_id: str
    observed_at: str
    poll_after_ms: Optional[int]
    sections: Tuple[RuntimeStatusSection, ...]


class BoundedConsoleRunner:
    """Run console commands without allowing descendants to outlive a timeout."""

    def __init__(
        self,
        executable: str = "console",
        timeout_seconds: int = DEFAULT_COMMAND_TIMEOUT_SECONDS,
        env: Optional[Mapping[str, str]] = None,
    ):
        self.executable = executable
        self.timeout_seconds = timeout_seconds
        self.env = dict(env) if env is not None else None

    def run(self, args: Sequence[str]) -> ConsoleCommandResult:
        process = subprocess.Popen(
            [self.executable, *args],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            env=self.env,
            start_new_session=True,
        )
        try:
            stdout, stderr = process.communicate(
                timeout=self.timeout_seconds,
            )
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                stdout, stderr = process.communicate(timeout=2)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                stdout, stderr = process.communicate()
            return ConsoleCommandResult(
                success=False,
                output=_bounded_text(stdout),
                error=(
                    "Status command timed out after "
                    f"{self.timeout_seconds} seconds."
                ),
            )
        return ConsoleCommandResult(
            success=process.returncode == 0,
            output=_bounded_text(stdout),
            error=_bounded_text(stderr) or None,
        )


class RuntimeStatusService:
    """Resolve canonical watcher status and bounded live console checks."""

    SUPPORTED_PLURALS = {
        "datasnapshots",
        "snapshotmigrations",
        "kafkaclusters",
        "capturedtraffics",
        "captureproxies",
        "trafficreplays",
    }

    def __init__(
        self,
        namespace: str,
        custom_api: Any,
        *,
        console_runner: Optional[Any] = None,
        clock: Callable[[], datetime] = lambda: datetime.now(timezone.utc),
        monotonic: Callable[[], float] = time.monotonic,
        cache_seconds: int = DEFAULT_CACHE_SECONDS,
    ):
        self.namespace = namespace
        self.custom_api = custom_api
        self.console_runner = console_runner or BoundedConsoleRunner()
        self.clock = clock
        self.monotonic = monotonic
        self.cache_seconds = cache_seconds
        self._cache: Dict[Tuple[str, str], Tuple[float, RuntimeStatus]] = {}
        self._inflight: Dict[Tuple[str, str], threading.Event] = {}
        self._lock = threading.Lock()

    def inspect(
        self,
        node_id: str,
        plural: str,
        name: str,
        *,
        force: bool = False,
    ) -> RuntimeStatus:
        if plural not in self.SUPPORTED_PLURALS:
            raise RuntimeStatusUnavailable(
                f"Runtime status is not available for {plural}/{name}."
            )
        cache_key = (plural, name)
        while True:
            with self._lock:
                cached = self._cache.get(cache_key)
                if (
                    not force
                    and cached
                    and cached[0] > self.monotonic()
                ):
                    return cached[1]
                inflight = self._inflight.get(cache_key)
                if inflight is None:
                    inflight = threading.Event()
                    self._inflight[cache_key] = inflight
                    break
            inflight.wait()
            force = False

        try:
            resource = self._resource(plural, name)
            if plural == "datasnapshots":
                sections = (self._snapshot_section(resource),)
            elif plural == "snapshotmigrations":
                sections = (self._backfill_section(resource),)
            elif plural == "kafkaclusters":
                sections = self._kafka_cluster_sections(name)
            elif plural == "capturedtraffics":
                sections = (self._captured_traffic_section(resource),)
            elif plural == "captureproxies":
                sections = (self._unsupported_section(
                    "proxy",
                    "Proxy runtime status",
                    "Proxy-specific status checks are not implemented yet.",
                ),)
            else:
                sections = (self._unsupported_section(
                    "replayer",
                    "Replayer runtime status",
                    "Replayer-specific status checks are not implemented yet.",
                ),)

            polling = any(
                section.state in {"running", "pending"}
                for section in sections
            )
            if plural in {"kafkaclusters", "capturedtraffics"}:
                polling = True
            result = RuntimeStatus(
                node_id=node_id,
                observed_at=self.clock().isoformat(),
                poll_after_ms=10_000 if polling else None,
                sections=sections,
            )
            with self._lock:
                self._cache[cache_key] = (
                    self.monotonic() + self.cache_seconds,
                    result,
                )
            return result
        finally:
            with self._lock:
                completed = self._inflight.pop(cache_key)
                completed.set()

    def _resource(self, plural: str, name: str) -> Mapping[str, Any]:
        try:
            return self.custom_api.get_namespaced_custom_object(
                group=CRD_GROUP,
                version=CRD_VERSION,
                namespace=self.namespace,
                plural=plural,
                name=name,
            )
        except ApiException as error:
            if error.status == 404:
                raise RuntimeStatusUnavailable(
                    f"The resource {plural}/{name} no longer exists."
                ) from error
            raise

    def _snapshot_section(
        self,
        resource: Mapping[str, Any],
    ) -> RuntimeStatusSection:
        status = resource.get("status") or {}
        creation = status.get("snapshotCreation") or {}
        phase = str(creation.get("phase") or status.get("phase") or "Pending")
        summary = creation.get("summary") or {}
        shards_total = summary.get("shardsTotal")
        shards_successful = summary.get("shardsSuccessful")
        headline = str(
            creation.get("message")
            or (
                f"Shards complete: {shards_successful or 0}/{shards_total}"
                if shards_total is not None
                else f"Snapshot is {phase.lower()}."
            )
        )
        return RuntimeStatusSection(
            key="snapshot",
            title="Snapshot progress",
            state=_phase_state(phase),
            summary=headline,
            source="console snapshot status watcher",
            details=_status_details(creation),
        )

    def _backfill_section(
        self,
        resource: Mapping[str, Any],
    ) -> RuntimeStatusSection:
        status = resource.get("status") or {}
        backfill = status.get("documentBackfill") or {}
        phase = str(backfill.get("phase") or status.get("phase") or "Pending")
        summary = backfill.get("summary") or {}
        percentage = summary.get("percentageCompleted")
        shards_total = summary.get("shardsTotal")
        shards_migrated = summary.get("shardsMigrated")
        headline_parts = []
        if percentage is not None:
            formatted_percentage = (
                f"{percentage:g}"
                if isinstance(percentage, (int, float))
                else str(percentage)
            )
            headline_parts.append(f"{formatted_percentage}% complete")
        if shards_total is not None:
            headline_parts.append(
                f"{shards_migrated or 0}/{shards_total} shards migrated"
            )
        headline = str(
            backfill.get("message")
            or ", ".join(headline_parts)
            or f"Document backfill is {phase.lower()}."
        )
        return RuntimeStatusSection(
            key="backfill",
            title="Document backfill",
            state=_phase_state(phase),
            summary=headline,
            source="console backfill status --deep-check watcher",
            details=_status_details(backfill),
        )

    def _kafka_cluster_sections(
        self,
        cluster_name: str,
    ) -> Tuple[RuntimeStatusSection, ...]:
        checks = (
            (
                "topics",
                "Kafka topics",
                ["kafka", "list-topics", "--kafka", cluster_name],
                "console kafka list-topics",
            ),
            (
                "consumer-groups",
                "Kafka consumer groups",
                ["kafka", "list-consumer-groups", "--kafka", cluster_name],
                "console kafka list-consumer-groups",
            ),
        )
        with ThreadPoolExecutor(max_workers=len(checks)) as executor:
            futures = [
                executor.submit(
                    self._console_section,
                    key,
                    title,
                    command,
                    source,
                )
                for key, title, command, source in checks
            ]
            return tuple(future.result() for future in futures)

    def _captured_traffic_section(
        self,
        resource: Mapping[str, Any],
    ) -> RuntimeStatusSection:
        spec = resource.get("spec") or {}
        cluster = str(spec.get("kafkaClusterName") or "default")
        topic = spec.get("topicName")
        if not topic:
            return RuntimeStatusSection(
                key="topic-records",
                title="Captured topic records",
                state="pending",
                summary="The Kafka topic name is not available yet.",
                source="console kafka describe-topic-records",
            )
        return self._console_section(
            "topic-records",
            "Captured topic records",
            [
                "kafka",
                "describe-topic-records",
                "--kafka",
                cluster,
                str(topic),
            ],
            "console kafka describe-topic-records",
        )

    def _console_section(
        self,
        key: str,
        title: str,
        command: Sequence[str],
        source: str,
    ) -> RuntimeStatusSection:
        result = self.console_runner.run(command)
        details = _detail_lines(result.output)
        if result.success:
            return RuntimeStatusSection(
                key=key,
                title=title,
                state="ok",
                summary=(
                    f"{len(details)} result line"
                    f"{'' if len(details) == 1 else 's'}."
                    if details
                    else "The command completed with no results."
                ),
                source=source,
                details=details,
            )
        error = result.error or result.output or "The status command failed."
        return RuntimeStatusSection(
            key=key,
            title=title,
            state="error",
            summary=error.splitlines()[-1],
            source=source,
            details=details,
        )

    @staticmethod
    def _unsupported_section(
        key: str,
        title: str,
        message: str,
    ) -> RuntimeStatusSection:
        return RuntimeStatusSection(
            key=key,
            title=title,
            state="unsupported",
            summary=message,
            source="not available",
        )


def _phase_state(phase: str) -> str:
    normalized = phase.lower()
    if normalized in {"completed", "succeeded", "ready"}:
        return "ok"
    if normalized in {"error", "failed", "completedwitherrors"}:
        return "error"
    if normalized in {"running", "starting", "terminating"}:
        return "running"
    return "pending"


def _status_details(status: Mapping[str, Any]) -> Tuple[str, ...]:
    details = []
    phase = status.get("phase")
    if phase:
        details.append(f"Phase: {phase}")
    for key, value in (status.get("summary") or {}).items():
        if value is not None:
            details.append(f"{_humanize(key)}: {value}")
    updated_at = status.get("updatedAt")
    if updated_at:
        details.append(f"Updated at: {updated_at}")
    return tuple(details)


def _humanize(value: str) -> str:
    result = []
    for character in value:
        if character.isupper() and result:
            result.append(" ")
        result.append(character.lower())
    return "".join(result).capitalize()


def _bounded_text(value: Optional[str]) -> str:
    return (value or "").strip()[:MAX_DETAIL_CHARACTERS]


def _detail_lines(value: str) -> Tuple[str, ...]:
    return tuple(
        line.rstrip()
        for line in value.splitlines()
        if line.strip()
    )[:MAX_DETAIL_LINES]

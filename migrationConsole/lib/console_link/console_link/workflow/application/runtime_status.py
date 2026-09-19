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
from typing import Any, Callable, Dict, Mapping, Optional, Sequence, Tuple, Union

from kubernetes.client.rest import ApiException

from console_link.models.kafka import parse_consumer_group_describe


CRD_GROUP = "migrations.opensearch.org"
CRD_VERSION = "v1alpha1"
DEFAULT_COMMAND_TIMEOUT_SECONDS = 20
DEFAULT_CACHE_SECONDS = 10
MAX_DETAIL_LINES = 60
MAX_DETAIL_CHARACTERS = 12_000
CAPTURED_TOPIC_RECORDS_TITLE = "Captured topic records"
DESCRIBE_TOPIC_RECORDS_SOURCE = "console kafka describe-topic-records"
CONSUMER_POSITIONS_TITLE = "Consumer positions"
DESCRIBE_CONSUMER_GROUP_SOURCE = (
    "console kafka describe-consumer-group --skip-time-lag"
)
CONFIGURED_CONSUMER_GROUPS_SOURCE = (
    "console kafka list-consumer-groups --configured-only"
)
TERMINAL_PHASES = {"completed", "succeeded", "ready"}
ETA_KEYS = {
    "eta",
    "etams",
    "estimatedcompletion",
    "estimatedcompletiontime",
    "estimatedtimeremaining",
}


class RuntimeStatusUnavailable(RuntimeError):
    """The requested resource cannot provide runtime status."""


@dataclass(frozen=True)
class ConsoleCommandResult:
    success: bool
    output: str
    error: Optional[str] = None


@dataclass(frozen=True)
class RuntimeStatusMetric:
    key: str
    label: str
    value: str | int | float | bool
    unit: Optional[str] = None


@dataclass(frozen=True)
class RuntimeStatusMetrics:
    metrics: Tuple[RuntimeStatusMetric, ...]


@dataclass(frozen=True)
class RuntimeStatusNameList:
    items: Tuple[str, ...]


@dataclass(frozen=True)
class RuntimeStatusTopicPartition:
    topic: str
    partition: int
    records: int


@dataclass(frozen=True)
class RuntimeStatusTopicPartitions:
    partitions: Tuple[RuntimeStatusTopicPartition, ...]


@dataclass(frozen=True)
class RuntimeStatusConsumerOffset:
    group: str
    topic: str
    partition: int
    current_offset: Optional[int]
    log_end_offset: Optional[int]
    lag: Optional[int]


@dataclass(frozen=True)
class RuntimeStatusConsumerOffsets:
    offsets: Tuple[RuntimeStatusConsumerOffset, ...]


@dataclass(frozen=True)
class RuntimeStatusText:
    lines: Tuple[str, ...]


RuntimeStatusContent = Union[
    RuntimeStatusMetrics,
    RuntimeStatusNameList,
    RuntimeStatusConsumerOffsets,
    RuntimeStatusTopicPartitions,
    RuntimeStatusText,
]


@dataclass(frozen=True)
class RuntimeStatusSection:
    key: str
    title: str
    state: str
    summary: str
    source: str
    content: Optional[RuntimeStatusContent] = None


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
        max_characters: int = MAX_DETAIL_CHARACTERS,
    ):
        self.executable = executable
        self.timeout_seconds = timeout_seconds
        self.env = dict(env) if env is not None else None
        self.max_characters = max_characters

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
                output=_bounded_text(stdout, self.max_characters),
                error=(
                    "Status command timed out after "
                    f"{self.timeout_seconds} seconds."
                ),
            )
        return ConsoleCommandResult(
            success=process.returncode == 0,
            output=_bounded_text(stdout, self.max_characters),
            error=_bounded_text(stderr, self.max_characters) or None,
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
        resource_phase: Optional[str] = None,
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
            resource = self._resource(
                plural,
                name,
                resource_phase=resource_phase,
            )
            if plural == "datasnapshots":
                sections = (self._snapshot_section(resource),)
            elif plural == "snapshotmigrations":
                sections = (self._backfill_section(resource),)
            elif plural == "kafkaclusters":
                sections = self._kafka_cluster_sections(name)
            elif plural == "capturedtraffics":
                sections = self._captured_traffic_sections(resource)
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

    def _resource(
        self,
        plural: str,
        name: str,
        *,
        resource_phase: Optional[str] = None,
    ) -> Mapping[str, Any]:
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
                phase = str(resource_phase or "").lower()
                creation_pending = any(
                    marker in phase
                    for marker in (
                        "creating",
                        "pending",
                        "running",
                        "submitting",
                        "waiting",
                    )
                )
                if creation_pending:
                    raise RuntimeStatusUnavailable(
                        f"The resource {plural}/{name} has not been created "
                        "yet. Runtime status will appear after Kubernetes "
                        "creates it."
                    ) from error
                raise RuntimeStatusUnavailable(
                    f"The resource {plural}/{name} is not currently present "
                    "in Kubernetes."
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
            content=_status_metrics(creation),
        )

    def _backfill_section(
        self,
        resource: Mapping[str, Any],
    ) -> RuntimeStatusSection:
        status = resource.get("status") or {}
        backfill = status.get("documentBackfill") or {}
        phase = str(backfill.get("phase") or status.get("phase") or "Pending")
        if not backfill:
            terminal = phase.lower() in TERMINAL_PHASES
            return RuntimeStatusSection(
                key="backfill",
                title="Document backfill",
                state="unsupported" if terminal else "pending",
                summary=(
                    "Document backfill was not run for this migration."
                    if terminal
                    else "Document backfill has not reported status yet."
                ),
                source="console backfill status --deep-check watcher",
            )
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
            content=_status_metrics(backfill),
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
                "topic",
            ),
            (
                "consumer-groups",
                "Configured consumer groups",
                [
                    "kafka",
                    "list-consumer-groups",
                    "--kafka",
                    cluster_name,
                    "--configured-only",
                ],
                CONFIGURED_CONSUMER_GROUPS_SOURCE,
                "consumer group",
            ),
        )
        with ThreadPoolExecutor(max_workers=len(checks)) as executor:
            futures = [
                executor.submit(
                    self._name_list_section,
                    key,
                    title,
                    command,
                    source,
                    item_label,
                )
                for key, title, command, source, item_label in checks
            ]
            return tuple(future.result() for future in futures)

    def _captured_traffic_sections(
        self,
        resource: Mapping[str, Any],
    ) -> Tuple[RuntimeStatusSection, ...]:
        return (
            self._captured_traffic_records_section(resource),
            self._captured_traffic_consumer_positions_section(resource),
        )

    def _captured_traffic_records_section(
        self,
        resource: Mapping[str, Any],
    ) -> RuntimeStatusSection:
        spec = resource.get("spec") or {}
        cluster = str(spec.get("kafkaClusterName") or "default")
        topic = spec.get("topicName")
        if not topic:
            return RuntimeStatusSection(
                key="topic-records",
                title=CAPTURED_TOPIC_RECORDS_TITLE,
                state="pending",
                summary="The Kafka topic name is not available yet.",
                source=DESCRIBE_TOPIC_RECORDS_SOURCE,
            )
        result = self.console_runner.run([
            "kafka",
            "describe-topic-records",
            "--kafka",
            cluster,
            str(topic),
        ])
        if not result.success:
            return _command_error_section(
                "topic-records",
                CAPTURED_TOPIC_RECORDS_TITLE,
                DESCRIBE_TOPIC_RECORDS_SOURCE,
                result,
            )
        partitions = _topic_partitions(result.output)
        total_records = sum(item.records for item in partitions)
        record_suffix = "" if total_records == 1 else "s"
        partition_suffix = "" if len(partitions) == 1 else "s"
        summary = "No topic partitions were reported."
        if partitions:
            summary = (
                f"{total_records} record{record_suffix} "
                f"across {len(partitions)} partition{partition_suffix}."
            )
        return RuntimeStatusSection(
            key="topic-records",
            title=CAPTURED_TOPIC_RECORDS_TITLE,
            state="ok",
            summary=summary,
            source=DESCRIBE_TOPIC_RECORDS_SOURCE,
            content=(
                RuntimeStatusTopicPartitions(partitions)
                if partitions
                else _text_content(result.output)
            ),
        )

    def _captured_traffic_consumer_positions_section(
        self,
        resource: Mapping[str, Any],
    ) -> RuntimeStatusSection:
        spec = resource.get("spec") or {}
        cluster = str(spec.get("kafkaClusterName") or "default")
        topic = str(spec.get("topicName") or "")
        if not topic:
            return RuntimeStatusSection(
                key="consumer-positions",
                title=CONSUMER_POSITIONS_TITLE,
                state="pending",
                summary="The Kafka topic name is not available yet.",
                source=DESCRIBE_CONSUMER_GROUP_SOURCE,
            )

        groups_result = self.console_runner.run([
            "kafka",
            "list-consumer-groups",
            "--kafka",
            cluster,
            "--configured-only",
        ])
        if not groups_result.success:
            return _command_error_section(
                "consumer-positions",
                CONSUMER_POSITIONS_TITLE,
                CONFIGURED_CONSUMER_GROUPS_SOURCE,
                groups_result,
            )
        groups = _detail_lines(groups_result.output)
        if not groups:
            return RuntimeStatusSection(
                key="consumer-positions",
                title=CONSUMER_POSITIONS_TITLE,
                state="ok",
                summary=(
                    "No replay consumer groups are configured for this "
                    "Kafka cluster."
                ),
                source=CONFIGURED_CONSUMER_GROUPS_SOURCE,
            )

        offsets, failures = _load_consumer_positions(
            self.console_runner,
            cluster,
            topic,
            groups,
        )
        return _consumer_positions_section(groups, offsets, failures)

    def _name_list_section(
        self,
        key: str,
        title: str,
        command: Sequence[str],
        source: str,
        item_label: str,
    ) -> RuntimeStatusSection:
        result = self.console_runner.run(command)
        if not result.success:
            return _command_error_section(
                key,
                title,
                source,
                result,
            )
        items = _detail_lines(result.output)
        item_suffix = "" if len(items) == 1 else "s"
        summary = f"No {item_label}s were reported."
        if items:
            summary = f"{len(items)} {item_label}{item_suffix}."
        return RuntimeStatusSection(
            key=key,
            title=title,
            state="ok",
            summary=summary,
            source=source,
            content=RuntimeStatusNameList(items),
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


def _load_consumer_positions(
    console_runner: Any,
    cluster: str,
    topic: str,
    groups: Sequence[str],
) -> tuple[list[RuntimeStatusConsumerOffset], list[str]]:
    commands = [
        [
            "kafka",
            "describe-consumer-group",
            "--kafka",
            cluster,
            "--skip-time-lag",
            group,
        ]
        for group in groups
    ]
    with ThreadPoolExecutor(max_workers=len(commands)) as executor:
        results = list(executor.map(console_runner.run, commands))

    offsets = []
    failures = []
    for group, result in zip(groups, results):
        if not result.success:
            failures.append(
                f"{group}: {result.error or result.output or 'status failed'}"
            )
            continue
        offsets.extend(
            offset
            for offset in _consumer_offsets(result.output, group)
            if offset.topic == topic
        )
    return offsets, failures


def _consumer_positions_section(
    groups: Sequence[str],
    offsets: Sequence[RuntimeStatusConsumerOffset],
    failures: Sequence[str],
) -> RuntimeStatusSection:
    content = (
        RuntimeStatusConsumerOffsets(tuple(offsets))
        if offsets
        else None
    )
    if failures:
        summary = (
            f"{len(failures)} of {len(groups)} consumer group checks failed."
        )
        if offsets:
            summary += (
                f" {len(offsets)} partition "
                f"position{'s' if len(offsets) != 1 else ''} available."
            )
        return RuntimeStatusSection(
            key="consumer-positions",
            title=CONSUMER_POSITIONS_TITLE,
            state="error",
            summary=summary,
            source=DESCRIBE_CONSUMER_GROUP_SOURCE,
            content=content or RuntimeStatusText(tuple(failures)),
        )
    if not offsets:
        return RuntimeStatusSection(
            key="consumer-positions",
            title=CONSUMER_POSITIONS_TITLE,
            state="ok",
            summary=(
                "No configured replay consumer has committed an offset "
                "for this topic."
            ),
            source=DESCRIBE_CONSUMER_GROUP_SOURCE,
        )

    group_count = len({offset.group for offset in offsets})
    return RuntimeStatusSection(
        key="consumer-positions",
        title=CONSUMER_POSITIONS_TITLE,
        state="ok",
        summary=(
            f"{len(offsets)} partition "
            f"position{'s' if len(offsets) != 1 else ''} across "
            f"{group_count} consumer "
            f"group{'s' if group_count != 1 else ''}."
        ),
        source=DESCRIBE_CONSUMER_GROUP_SOURCE,
        content=content,
    )


def _command_error_section(
    key: str,
    title: str,
    source: str,
    result: ConsoleCommandResult,
) -> RuntimeStatusSection:
    error = result.error or result.output or "The status command failed."
    return RuntimeStatusSection(
        key=key,
        title=title,
        state="error",
        summary=error.splitlines()[-1],
        source=source,
        content=_text_content(result.output),
    )


def _text_content(value: str) -> Optional[RuntimeStatusText]:
    lines = _detail_lines(value)
    return RuntimeStatusText(lines) if lines else None


def _topic_partitions(
    value: str,
) -> Tuple[RuntimeStatusTopicPartition, ...]:
    partitions = []
    for line in _detail_lines(value):
        fields = line.split()
        if len(fields) < 3:
            continue
        try:
            partition = int(fields[-2])
            records = int(fields[-1])
        except ValueError:
            continue
        partitions.append(RuntimeStatusTopicPartition(
            topic=" ".join(fields[:-2]),
            partition=partition,
            records=records,
        ))
    return tuple(partitions)


def _consumer_offsets(
    value: str,
    group: str,
) -> Tuple[RuntimeStatusConsumerOffset, ...]:
    return tuple(
        RuntimeStatusConsumerOffset(
            group=group,
            topic=str(row["topic"]),
            partition=int(row["partition"]),
            current_offset=int(row["current_offset"]),
            log_end_offset=int(row["log_end_offset"]),
            lag=int(row["lag"]),
        )
        for row in parse_consumer_group_describe(value)
    )


def _status_metrics(
    status: Mapping[str, Any],
) -> Optional[RuntimeStatusMetrics]:
    metrics = []
    phase = status.get("phase")
    terminal = str(phase or "").lower() in TERMINAL_PHASES
    if phase:
        metrics.append(RuntimeStatusMetric(
            key="phase",
            label="Phase",
            value=str(phase),
        ))
    summary = status.get("summary") or {}
    metrics.extend(_summary_metrics(summary, terminal))
    updated_at = status.get("updatedAt")
    if updated_at:
        metrics.append(RuntimeStatusMetric(
            key="updatedAt",
            label="Updated at",
            value=str(updated_at),
        ))
    return RuntimeStatusMetrics(tuple(metrics)) if metrics else None


def _summary_metrics(
    summary: Mapping[str, Any],
    terminal: bool,
) -> list[RuntimeStatusMetric]:
    metrics = []
    for key, value in summary.items():
        if key == "dataProcessedUnit":
            continue
        if terminal and key.lower() in ETA_KEYS:
            continue
        if value is None:
            continue
        metrics.append(RuntimeStatusMetric(
            key=key,
            label=_humanize(key),
            value=_metric_value(value),
            unit=_metric_unit(key, summary),
        ))
    return metrics


def _metric_unit(key: str, summary: Mapping[str, Any]) -> Optional[str]:
    if key == "percentageCompleted":
        return "percent"
    if key == "dataProcessed":
        return str(summary.get("dataProcessedUnit") or "MiB")
    return None


def _metric_value(value: Any) -> str | int | float | bool:
    if isinstance(value, (str, int, float, bool)):
        return value
    return str(value)


def _phase_state(phase: str) -> str:
    normalized = phase.lower()
    if normalized in {"completed", "succeeded", "ready"}:
        return "ok"
    if normalized in {"error", "failed", "completedwitherrors"}:
        return "error"
    if normalized in {"running", "starting", "terminating"}:
        return "running"
    return "pending"


def _humanize(value: str) -> str:
    result = []
    for character in value:
        if character.isupper() and result:
            result.append(" ")
        result.append(character.lower())
    return "".join(result).capitalize()


def _bounded_text(
    value: Optional[str],
    max_characters: int = MAX_DETAIL_CHARACTERS,
) -> str:
    return (value or "").strip()[:max_characters]


def _detail_lines(value: str) -> Tuple[str, ...]:
    return tuple(
        line.rstrip()
        for line in value.splitlines()
        if line.strip()
    )[:MAX_DETAIL_LINES]

from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import threading

from kubernetes.client.exceptions import ApiException
import pytest

from console_link.workflow.application.runtime_status import (
    ConsoleCommandResult,
    RuntimeStatusConsumerOffsets,
    RuntimeStatusMetrics,
    RuntimeStatusNameList,
    RuntimeStatusService,
    RuntimeStatusTopicPartitions,
)


class _CustomApi:
    def __init__(self, resources):
        self.resources = resources
        self.calls = []

    def get_namespaced_custom_object(self, **request):
        self.calls.append(request)
        return self.resources[(request["plural"], request["name"])]


class _MissingCustomApi:
    def get_namespaced_custom_object(self, **request):
        raise ApiException(status=404, reason="Not Found")


class _Console:
    def __init__(self, results):
        self.results = results
        self.calls = []

    def run(self, command):
        self.calls.append(command)
        return self.results[tuple(command)]


def _service(resources, console=None):
    return RuntimeStatusService(
        "ma",
        _CustomApi(resources),
        console_runner=console or _Console({}),
        clock=lambda: datetime(2026, 8, 30, 14, 0, tzinfo=timezone.utc),
        monotonic=lambda: 10.0,
    )


def test_snapshot_status_uses_canonical_console_watcher_result():
    service = _service({
        ("datasnapshots", "source-snapshot"): {
            "status": {
                "phase": "Running",
                "snapshotCreation": {
                    "phase": "Running",
                    "message": "Snapshot is 50% complete",
                    "summary": {
                        "shardsSuccessful": 4,
                        "shardsTotal": 8,
                    },
                    "updatedAt": "2026-08-30T13:59:00Z",
                },
            },
        },
    })

    result = service.inspect(
        "resource:datasnapshots:source-snapshot",
        "datasnapshots",
        "source-snapshot",
    )

    assert result.poll_after_ms == 10_000
    assert result.sections[0].state == "running"
    assert result.sections[0].source == "console snapshot status watcher"
    assert isinstance(result.sections[0].content, RuntimeStatusMetrics)
    assert {
        metric.key: metric.value
        for metric in result.sections[0].content.metrics
    } == {
        "phase": "Running",
        "shardsSuccessful": 4,
        "shardsTotal": 8,
        "updatedAt": "2026-08-30T13:59:00Z",
    }


def test_backfill_status_uses_deep_check_watcher_result():
    service = _service({
        ("snapshotmigrations", "migration-0"): {
            "status": {
                "documentBackfill": {
                    "phase": "Running",
                    "summary": {
                        "percentageCompleted": 25,
                        "shardsMigrated": 1,
                        "shardsTotal": 4,
                    },
                },
            },
        },
    })

    result = service.inspect(
        "resource:snapshotmigrations:migration-0",
        "snapshotmigrations",
        "migration-0",
    )

    assert result.sections[0].summary == (
        "25% complete, 1/4 shards migrated"
    )
    assert "backfill status --deep-check" in result.sections[0].source


def test_completed_migration_without_backfill_status_reports_not_run():
    service = _service({
        ("snapshotmigrations", "migration-0"): {
            "status": {"phase": "Completed"},
        },
    })

    result = service.inspect(
        "resource:snapshotmigrations:migration-0",
        "snapshotmigrations",
        "migration-0",
    )

    assert result.sections[0].state == "unsupported"
    assert result.sections[0].summary == (
        "Document backfill was not run for this migration."
    )
    assert result.sections[0].content is None


def test_completed_status_omits_eta_and_preserves_data_unit():
    service = _service({
        ("datasnapshots", "source-snapshot"): {
            "status": {
                "phase": "Completed",
                "snapshotCreation": {
                    "phase": "Completed",
                    "summary": {
                        "shardsSuccessful": 8,
                        "shardsTotal": 8,
                        "dataProcessed": 512,
                        "dataProcessedUnit": "MiB",
                        "eta": "0h 0m 0s",
                        "etaMs": 0,
                    },
                },
            },
        },
    })

    result = service.inspect(
        "resource:datasnapshots:source-snapshot",
        "datasnapshots",
        "source-snapshot",
    )

    content = result.sections[0].content
    assert isinstance(content, RuntimeStatusMetrics)
    metrics = {metric.key: metric for metric in content.metrics}
    assert metrics["dataProcessed"].unit == "MiB"
    assert "dataProcessedUnit" not in metrics
    assert "eta" not in metrics
    assert "etaMs" not in metrics


def test_missing_running_resource_reports_that_creation_is_pending():
    service = RuntimeStatusService(
        "ma",
        _MissingCustomApi(),
        console_runner=_Console({}),
    )

    with pytest.raises(
        RuntimeError,
        match=(
            r"The resource trafficreplays/replay has not been created yet\. "
            r"Runtime status will appear after Kubernetes creates it\."
        ),
    ):
        service.inspect(
            "resource:trafficreplays:replay",
            "trafficreplays",
            "replay",
            resource_phase="Running",
        )


def test_missing_terminal_resource_uses_neutral_present_tense():
    service = RuntimeStatusService(
        "ma",
        _MissingCustomApi(),
        console_runner=_Console({}),
    )

    with pytest.raises(
        RuntimeError,
        match=(
            r"The resource trafficreplays/replay is not currently present "
            r"in Kubernetes\."
        ),
    ) as raised:
        service.inspect(
            "resource:trafficreplays:replay",
            "trafficreplays",
            "replay",
            resource_phase="Succeeded",
        )

    assert "no longer" not in str(raised.value)


def test_kafka_cluster_runs_bounded_inventory_commands():
    console = _Console({
        ("kafka", "list-topics", "--kafka", "default"):
            ConsoleCommandResult(True, "capture\n__consumer_offsets"),
        (
            "kafka",
            "list-consumer-groups",
            "--kafka",
            "default",
            "--configured-only",
        ):
            ConsoleCommandResult(True, "replayer-target"),
    })
    service = _service({
        ("kafkaclusters", "default"): {"status": {"phase": "Ready"}},
    }, console)

    result = service.inspect(
        "resource:kafkaclusters:default",
        "kafkaclusters",
        "default",
    )

    assert {tuple(call) for call in console.calls} == {
        ("kafka", "list-topics", "--kafka", "default"),
        (
            "kafka",
            "list-consumer-groups",
            "--kafka",
            "default",
            "--configured-only",
        ),
    }
    assert [section.state for section in result.sections] == ["ok", "ok"]
    assert result.sections[0].summary == "2 topics."
    assert result.sections[0].content == RuntimeStatusNameList((
        "capture",
        "__consumer_offsets",
    ))


def test_captured_traffic_checks_the_exact_topic():
    records_command = (
        "kafka",
        "describe-topic-records",
        "--kafka",
        "default",
        "capture",
    )
    groups_command = (
        "kafka",
        "list-consumer-groups",
        "--kafka",
        "default",
        "--configured-only",
    )
    console = _Console({
        records_command: ConsoleCommandResult(
            True,
            "TOPIC PARTITION RECORDS\ncapture 0 125",
        ),
        groups_command: ConsoleCommandResult(True, ""),
    })
    service = _service({
        ("capturedtraffics", "capture-topic"): {
            "spec": {
                "kafkaClusterName": "default",
                "topicName": "capture",
            },
        },
    }, console)

    result = service.inspect(
        "resource:capturedtraffics:capture-topic",
        "capturedtraffics",
        "capture-topic",
    )

    assert console.calls == [list(records_command), list(groups_command)]
    assert result.sections[0].summary == "125 records across 1 partition."
    content = result.sections[0].content
    assert isinstance(content, RuntimeStatusTopicPartitions)
    assert len(content.partitions) == 1
    partition = content.partitions[0]
    assert (partition.topic, partition.partition, partition.records) == (
        "capture",
        0,
        125,
    )


def test_captured_traffic_reports_consumer_cursor_for_its_topic():
    console = _Console({
        (
            "kafka",
            "describe-topic-records",
            "--kafka",
            "main-k",
            "capture",
        ): ConsoleCommandResult(
            True,
            "TOPIC PARTITION RECORDS\ncapture 0 57240",
        ),
        (
            "kafka",
            "list-consumer-groups",
            "--kafka",
            "main-k",
            "--configured-only",
        ): ConsoleCommandResult(True, "replayer-target\n"),
        (
            "kafka",
            "describe-consumer-group",
            "--kafka",
            "main-k",
            "--skip-time-lag",
            "replayer-target",
        ): ConsoleCommandResult(
            True,
            (
                "GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG "
                "CONSUMER-ID HOST CLIENT-ID\n"
                "replayer-target capture 0 500 57240 56740 - - -\n"
                "replayer-target another-topic 0 10 10 0 - - -"
            ),
        ),
    })
    service = _service({
        ("capturedtraffics", "capture-topic"): {
            "spec": {
                "kafkaClusterName": "main-k",
                "topicName": "capture",
            },
        },
    }, console)

    result = service.inspect(
        "resource:capturedtraffics:capture-topic",
        "capturedtraffics",
        "capture-topic",
    )

    positions = result.sections[1]
    assert positions.title == "Consumer positions"
    assert positions.state == "ok"
    assert positions.summary == (
        "1 partition position across 1 consumer group."
    )
    assert isinstance(positions.content, RuntimeStatusConsumerOffsets)
    assert len(positions.content.offsets) == 1
    offset = positions.content.offsets[0]
    assert (
        offset.group,
        offset.topic,
        offset.partition,
        offset.current_offset,
        offset.log_end_offset,
        offset.lag,
    ) == (
        "replayer-target",
        "capture",
        0,
        500,
        57240,
        56740,
    )


def test_proxy_and_replayer_return_explicit_placeholders():
    resources = {
        ("captureproxies", "capture"): {},
        ("trafficreplays", "replay"): {},
    }
    service = _service(resources)

    proxy = service.inspect(
        "resource:captureproxies:capture",
        "captureproxies",
        "capture",
    )
    replay = service.inspect(
        "resource:trafficreplays:replay",
        "trafficreplays",
        "replay",
    )

    assert proxy.sections[0].state == "unsupported"
    assert replay.sections[0].state == "unsupported"


def test_concurrent_kafka_requests_share_one_bounded_check():
    started = threading.Event()
    release = threading.Event()

    class _BlockingConsole:
        def __init__(self):
            self.calls = []
            self.lock = threading.Lock()

        def run(self, command):
            with self.lock:
                self.calls.append(command)
                if len(self.calls) == 2:
                    started.set()
            release.wait(timeout=2)
            return ConsoleCommandResult(True, "ready")

    console = _BlockingConsole()
    service = _service({
        ("kafkaclusters", "default"): {"status": {"phase": "Ready"}},
    }, console)

    with ThreadPoolExecutor(max_workers=2) as executor:
        first = executor.submit(
            service.inspect,
            "resource:kafkaclusters:default",
            "kafkaclusters",
            "default",
        )
        assert started.wait(timeout=2)
        second = executor.submit(
            service.inspect,
            "resource:kafkaclusters:default",
            "kafkaclusters",
            "default",
        )
        release.set()
        first_result = first.result(timeout=2)
        second_result = second.result(timeout=2)

    assert first_result == second_result
    assert len(console.calls) == 2

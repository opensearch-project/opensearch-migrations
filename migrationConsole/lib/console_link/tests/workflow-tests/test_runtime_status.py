from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import threading

from console_link.workflow.application.runtime_status import (
    ConsoleCommandResult,
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


def test_kafka_cluster_runs_bounded_inventory_commands():
    console = _Console({
        ("kafka", "list-topics", "--kafka", "default"):
            ConsoleCommandResult(True, "capture\n__consumer_offsets"),
        ("kafka", "list-consumer-groups", "--kafka", "default"):
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
        ("kafka", "list-consumer-groups", "--kafka", "default"),
    }
    assert [section.state for section in result.sections] == ["ok", "ok"]
    assert result.sections[0].summary == "2 topics."
    assert result.sections[0].content == RuntimeStatusNameList((
        "capture",
        "__consumer_offsets",
    ))


def test_captured_traffic_checks_the_exact_topic():
    command = (
        "kafka",
        "describe-topic-records",
        "--kafka",
        "default",
        "capture",
    )
    console = _Console({
        command: ConsoleCommandResult(
            True,
            "TOPIC PARTITION RECORDS\ncapture 0 125",
        ),
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

    assert console.calls == [list(command)]
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

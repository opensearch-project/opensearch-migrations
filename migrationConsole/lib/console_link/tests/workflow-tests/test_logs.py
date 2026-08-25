from types import SimpleNamespace

import pytest

from console_link.workflow.application.logs import (
    KubernetesLogSource,
    LogRecord,
    LogSelection,
    LogStreamService,
    LogUnavailable,
    MIGRATION_RESOURCE_UID_LABEL,
    resource_log_selector,
)


def _pod(
    name="capture-0",
    uid="pod-uid",
    *,
    restart_count=0,
    node_id=None,
):
    annotations = {}
    if node_id:
        annotations["workflows.argoproj.io/node-id"] = node_id
    return {
        "metadata": {
            "name": name,
            "uid": uid,
            "annotations": annotations,
        },
        "spec": {
            "containers": [
                {"name": "capture-proxy"},
                {"name": "metrics"},
            ],
        },
        "status": {
            "containerStatuses": [
                {
                    "name": "capture-proxy",
                    "restartCount": restart_count,
                },
                {
                    "name": "metrics",
                    "restartCount": 0,
                },
            ],
        },
    }


def test_resource_log_selector_uses_exact_resource_ownership():
    selector = resource_log_selector({
        "metadata": {
            "uid": "resource-uid",
            "labels": {
                "migrations.opensearch.org/source": "source-a",
                "migrations.opensearch.org/run-number": "4",
                "migrations.opensearch.org/workflow-name": "migration",
                "strimzi.io/cluster": "capture-kafka",
                "unrelated": "ignored",
            },
        },
    })

    assert selector == f"{MIGRATION_RESOURCE_UID_LABEL}=resource-uid"


def test_resource_log_selector_requires_a_resource_uid():
    with pytest.raises(LogUnavailable, match="no Kubernetes UID"):
        resource_log_selector({"metadata": {"labels": {"unrelated": "x"}}})


class _CustomObjects:
    def __init__(self, resource):
        self.resource = resource
        self.request = None

    def get_namespaced_custom_object(self, **kwargs):
        self.request = kwargs
        return self.resource


class _Core:
    def __init__(self, pods):
        self.pods = pods
        self.selector = None
        self.log_requests = []

    def list_namespaced_pod(self, namespace, label_selector):
        self.selector = label_selector
        return SimpleNamespace(items=self.pods)

    def read_namespaced_pod_log(self, **kwargs):
        self.log_requests.append(kwargs)
        return (
            "2026-08-13T20:00:00.123456Z first line\n"
            "2026-08-13T20:00:01Z second line\n"
        )


def test_kubernetes_source_lists_aggregate_current_and_previous_targets():
    custom = _CustomObjects({
        "metadata": {
            "uid": "capture-resource-uid",
            "labels": {
                "migrations.opensearch.org/capture": "p2",
            },
        },
    })
    core = _Core([_pod(restart_count=2)])
    source = KubernetesLogSource(
        namespace="ma",
        workflow_name="migration",
        core_api=core,
        custom_api=custom,
    )

    selections = source.resolve("logs:captureproxies:p2")

    assert [selection.kind for selection in selections] == [
        "aggregate",
        "container",
        "container",
        "container",
    ]
    assert selections[0].label == "All matching containers"
    assert selections[1].container == "capture-proxy"
    assert selections[1].previous is False
    assert selections[2].container == "capture-proxy"
    assert selections[2].previous is True
    assert selections[2].restart_count == 1
    assert selections[3].container == "metrics"
    assert core.selector == (
        f"{MIGRATION_RESOURCE_UID_LABEL}=capture-resource-uid"
    )
    assert custom.request["plural"] == "captureproxies"
    assert custom.request["name"] == "p2"


def test_kubernetes_source_resolves_workflow_step_by_argo_node_annotation():
    core = _Core([
        _pod(name="other", uid="other", node_id="node-other"),
        _pod(name="wanted", uid="wanted", node_id="node-123"),
    ])
    source = KubernetesLogSource(
        namespace="ma",
        workflow_name="migration",
        core_api=core,
        custom_api=_CustomObjects({}),
    )

    selections = source.resolve("logs:workflow-step:node-123")

    assert all(selection.pod_name == "wanted" for selection in selections)
    assert core.selector == "workflows.argoproj.io/workflow=migration"


def test_kubernetes_history_decodes_stringified_bytes_from_client():
    core = _Core([_pod()])
    core.read_namespaced_pod_log = lambda **_kwargs: (
        "b'2026-08-13T20:00:00.123456789Z first line\\n"
        "2026-08-13T20:00:01Z second line\\n'"
    )
    source = KubernetesLogSource(
        namespace="ma",
        workflow_name="migration",
        core_api=core,
        custom_api=_CustomObjects({
            "metadata": {
                "uid": "capture-resource-uid",
                "labels": {
                    "migrations.opensearch.org/capture": "p2",
                },
            },
        }),
    )
    selection = source.resolve("logs:captureproxies:p2")[1]

    records = source.history(selection, 20)

    assert [record.timestamp for record in records] == [
        "2026-08-13T20:00:00.123456789Z",
        "2026-08-13T20:00:01Z",
    ]
    assert [record.message for record in records] == [
        "first line",
        "second line",
    ]


class _Source:
    def __init__(self):
        self.selection = LogSelection(
            kind="container",
            label="capture-0 / capture-proxy",
            selector=None,
            pod_name="capture-0",
            pod_uid="pod-uid",
            container="capture-proxy",
            restart_count=0,
            previous=False,
        )
        self.follow_started = False
        self.follow_stopped = False

    def resolve(self, capability_target_id):
        assert capability_target_id == "logs:captureproxies:p2"
        return (self.selection,)

    def history(self, selection, tail_lines):
        assert selection == self.selection
        assert tail_lines == 4
        return tuple(
            LogRecord(
                timestamp=f"2026-08-13T20:00:0{index}Z",
                pod_name="capture-0",
                pod_uid="pod-uid",
                container="capture-proxy",
                restart_count=0,
                previous=False,
                message=f"line {index}",
            )
            for index in range(4)
        )

    def follow(self, selection, emit, stop, register_response):
        self.follow_started = True
        emit(LogRecord(
            timestamp="2026-08-13T20:00:04Z",
            pod_name="capture-0",
            pod_uid="pod-uid",
            container="capture-proxy",
            restart_count=0,
            previous=False,
            message="followed line",
        ))
        stop.wait(1)
        self.follow_stopped = True


def test_log_stream_pages_are_bounded_cursor_based_and_cancellable():
    source = _Source()
    service = LogStreamService(
        source,
        max_lines=4,
        max_bytes=1024,
    )
    inventory = service.list_targets(
        "resource:captureproxies:p2",
        "logs:captureproxies:p2",
    )

    stream = service.start(
        inventory.targets[0].id,
        tail_lines=4,
        follow=True,
        page_size=2,
    )
    followed = service.wait_for_events(
        stream.id,
        after_sequence=stream.page.events[-1].sequence,
        timeout=1,
    )
    latest = service.page(stream.id, limit=2)
    older = service.page(
        stream.id,
        before=latest.before_cursor,
        limit=2,
    )

    assert [event.message for event in followed] == ["followed line"]
    assert [event.message for event in latest.events] == [
        "line 3",
        "followed line",
    ]
    assert [event.message for event in older.events] == ["line 1", "line 2"]
    assert older.at_available_start is True
    assert older.history_truncated is True

    stopped = service.stop(stream.id)
    assert stopped.state == "stopped"
    assert source.follow_stopped is True
    service.shutdown()

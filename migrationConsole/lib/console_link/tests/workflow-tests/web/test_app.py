import json
from datetime import datetime, timezone
from fastapi.testclient import TestClient
import pytest

from console_link.workflow.application.external_resources import (
    ExternalResourceDetails,
    ExternalResourceInventory,
    ExternalResourceMutation,
)
from console_link.workflow.application.config_documents import (
    ConfigurationDocument,
    ConfigurationDocumentConflict,
)
from console_link.workflow.application.connectivity import (
    ConnectivityTarget,
    PreparedConnectivityChecks,
)
from console_link.workflow.application.config_review import ConfigReviewChange
from console_link.workflow.application.config_submission import (
    PreparedConfigSubmission,
    SavedConfigReview,
)
from console_link.workflow.application.logs import (
    LogEvent,
    LogPage,
    LogStream,
    LogStreamStatus,
    LogTarget,
    LogTargetInventory,
)
from console_link.workflow.application.models import (
    ManageCapability,
    ManageNode,
    ManageSnapshot,
)
from console_link.workflow.application.observations import (
    Observation,
    ObservationEvent,
)
from console_link.workflow.application.outputs import (
    OutputContent,
    OutputDescriptor,
    OutputInventory,
    OutputReadFailed,
    OutputStale,
)
from console_link.workflow.application.operations import (
    Operation,
    OperationEvent,
)
from console_link.workflow.application.actions import (
    ApprovalGateInventory,
    ApprovalGateSummary,
    ApprovalReview,
)
from console_link.workflow.services.admission_preflight import (
    AdmissionDeploymentAction,
    AdmissionPreflightIssue,
    AdmissionPreflightReport,
)
from console_link.workflow.application.resets import (
    ResetExecutionResult,
    ResetPlan,
    ResetPlanStale,
    ResetTarget,
)
from console_link.workflow.application.runtime_status import (
    RuntimeStatus,
    RuntimeStatusMetric,
    RuntimeStatusMetrics,
    RuntimeStatusSection,
)
from console_link.workflow.web.app import WebAppSettings, create_app
from console_link.workflow.web.openapi import main as generate_openapi


def _static_bundle(tmp_path):
    static_dir = tmp_path / "static"
    assets_dir = static_dir / "assets"
    assets_dir.mkdir(parents=True)
    (static_dir / "index.html").write_text(
        '<!doctype html><div id="root">workflow manage</div>',
        encoding="utf-8",
    )
    (assets_dir / "app.js").write_text("window.manage = true;", encoding="utf-8")
    return static_dir


def _snapshot():
    node = ManageNode(
        id="resource:captureproxies:capture",
        revision="node-revision",
        kind="resource",
        label="capture",
        status="ok",
    )
    return ManageSnapshot(
        format_version=1,
        revision="snapshot-revision",
        observed_at=datetime.now(timezone.utc).isoformat(),
        namespace="ma",
        workflow_name="migration",
        workflow=None,
        root_ids=(node.id,),
        nodes={node.id: node},
    )


def test_health_endpoint_is_same_origin_and_does_not_enable_cors(tmp_path):
    app = create_app(static_dir=_static_bundle(tmp_path))

    with TestClient(app) as client:
        response = client.get(
            "/api/v1/system/health",
            headers={"Origin": "https://untrusted.example"},
        )

    assert response.status_code == 200
    assert response.json() == {
        "status": "ok",
        "apiVersion": "v1",
    }
    assert "access-control-allow-origin" not in response.headers


def test_static_assets_and_spa_routes_are_served_from_the_same_app(tmp_path):
    app = create_app(static_dir=_static_bundle(tmp_path))

    with TestClient(app) as client:
        shell = client.get("/")
        client_route = client.get("/resources/capture")
        asset = client.get("/assets/app.js")

    assert shell.status_code == 200
    assert shell.headers["content-type"].startswith("text/html")
    assert client_route.status_code == 200
    assert "workflow manage" in client_route.text
    assert asset.status_code == 200
    assert asset.text == "window.manage = true;"


def test_unknown_api_route_is_json_404_not_the_spa(tmp_path):
    app = create_app(static_dir=_static_bundle(tmp_path))

    with TestClient(app) as client:
        response = client.get("/api/v1/not-a-route")

    assert response.status_code == 404
    assert response.headers["content-type"].startswith("application/json")
    assert response.json() == {"detail": "Not Found"}


def test_missing_bundle_returns_actionable_503(tmp_path):
    app = create_app(static_dir=tmp_path / "missing")

    with TestClient(app) as client:
        response = client.get("/")

    assert response.status_code == 503
    assert response.json()["detail"].startswith("Workflow Manage web assets")


def test_openapi_exposes_the_versioned_manage_snapshot_contract(tmp_path):
    app = create_app(static_dir=_static_bundle(tmp_path))

    schemas = app.openapi()["components"]["schemas"]

    assert "ManageSnapshotV1" in schemas
    assert "ManageNodeV1" in schemas
    assert schemas["ManageSnapshotV1"]["properties"]["formatVersion"]["const"] == 1
    assert schemas["ManageNodeV1"]["properties"]["configState"] == {
        "anyOf": [
            {"$ref": "#/components/schemas/ConfigNodeStateV1"},
            {"type": "null"},
        ],
        "default": None,
    }
    assert schemas["ManageNodeV1"]["properties"]["activityAt"] == {
        "anyOf": [
            {"format": "date-time", "type": "string"},
            {"type": "null"},
        ],
        "default": None,
        "title": "Activityat",
    }
    assert "ConfigDraftV1" not in schemas
    assert "ApplyEditOperationRequestV1" not in schemas


def test_openapi_generator_writes_current_application_contract(tmp_path):
    output = tmp_path / "openapi.json"

    generate_openapi(["--output", str(output)])

    assert json.loads(output.read_text(encoding="utf-8")) == create_app().openapi()


class _Coordinator:
    def __init__(self, observation, events=()):
        self.observation = observation
        self.events = events
        self.started = False
        self.stopped = False
        self.event_cursor = None
        self.config_invalidations = []

    async def start(self):
        self.started = True

    async def stop(self):
        self.stopped = True

    async def get_observation(self):
        if isinstance(self.observation, Exception):
            raise self.observation
        return self.observation

    @property
    def current_observation(self):
        return (
            self.observation
            if isinstance(self.observation, Observation)
            else None
        )

    async def stream_events(self, last_event_id):
        self.event_cursor = last_event_id
        for event in self.events:
            yield event

    def invalidate_saved_configuration(self, persisted_revision):
        self.config_invalidations.append(persisted_revision)


def test_manage_state_uses_the_shared_coordinator_and_app_lifecycle(tmp_path):
    snapshot = _snapshot()
    coordinator = _Coordinator(Observation(snapshot=snapshot))
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        coordinator=coordinator,
    )

    with TestClient(app) as client:
        response = client.get("/api/v1/manage/state")
        assert coordinator.started is True

    assert coordinator.stopped is True
    assert response.status_code == 200
    assert response.json()["revision"] == snapshot.revision
    assert "resource:captureproxies:capture" in response.json()["nodes"]


def test_manage_state_dependency_failure_is_service_unavailable(tmp_path):
    coordinator = _Coordinator(ValueError("cluster client is unavailable"))
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        coordinator=coordinator,
    )

    with TestClient(app, raise_server_exceptions=False) as client:
        response = client.get("/api/v1/manage/state")

    assert response.status_code == 503
    assert response.json()["detail"] == "cluster client is unavailable"


def test_manage_events_replays_from_last_event_id_as_sse(tmp_path):
    coordinator = _Coordinator(
        Observation(snapshot=_snapshot()),
        events=(
            ObservationEvent(
                id=8,
                event="state-invalidated",
                data={"stale": False, "revision": "next"},
            ),
            ObservationEvent(
                id=9,
                event="heartbeat",
                data={"sentAt": "2026-08-12T16:00:00Z"},
            ),
        ),
    )
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        coordinator=coordinator,
    )

    with TestClient(app) as client:
        response = client.get(
            "/api/v1/manage/events",
            headers={"Last-Event-ID": "7"},
        )

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    assert response.headers["cache-control"] == "no-cache"
    assert coordinator.event_cursor == 7
    assert response.text == (
        'id: 8\nevent: state-invalidated\n'
        'data: {"revision":"next","stale":false}\n\n'
        'id: 9\nevent: heartbeat\n'
        'data: {"sentAt":"2026-08-12T16:00:00Z"}\n\n'
    )


def test_manage_state_without_a_runtime_coordinator_is_unavailable(tmp_path):
    app = create_app(static_dir=_static_bundle(tmp_path))

    with TestClient(app) as client:
        response = client.get("/api/v1/manage/state")

    assert response.status_code == 503
    assert response.json()["detail"] == "Workflow observation is not configured"


class _RuntimeStatus:
    def __init__(self):
        self.calls = []

    def inspect(self, node_id, plural, name, *, force=False):
        self.calls.append((node_id, plural, name, force))
        return RuntimeStatus(
            node_id=node_id,
            observed_at="2026-08-30T14:00:00+00:00",
            poll_after_ms=10_000,
            sections=(
                RuntimeStatusSection(
                    key="snapshot",
                    title="Snapshot progress",
                    state="running",
                    summary="Snapshot is 50% complete",
                    source="console snapshot status watcher",
                    content=RuntimeStatusMetrics((
                        RuntimeStatusMetric(
                            key="shardsSuccessful",
                            label="Shards successful",
                            value=4,
                        ),
                        RuntimeStatusMetric(
                            key="shardsTotal",
                            label="Shards total",
                            value=8,
                        ),
                    )),
                ),
            ),
        )


def test_runtime_status_resolves_the_observed_resource_node(tmp_path):
    snapshot = _snapshot()
    node_id = "resource:captureproxies:capture"
    node = ManageNode(
        **{
            **snapshot.nodes[node_id].__dict__,
            "resource_plural": "datasnapshots",
            "resource_name": "source-snapshot",
        }
    )
    snapshot = ManageSnapshot(
        **{
            **snapshot.__dict__,
            "nodes": {node_id: node},
        }
    )
    status = _RuntimeStatus()
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        coordinator=_Coordinator(Observation(snapshot=snapshot)),
        runtime_status=status,
    )

    with TestClient(app) as client:
        response = client.get(
            f"/api/v1/nodes/{node_id}/runtime-status",
            params={"force": "true"},
        )

    assert response.status_code == 200
    assert response.json() == {
        "nodeId": node_id,
        "observedAt": "2026-08-30T14:00:00Z",
        "pollAfterMs": 10_000,
        "sections": [{
            "key": "snapshot",
            "title": "Snapshot progress",
            "state": "running",
            "summary": "Snapshot is 50% complete",
            "source": "console snapshot status watcher",
            "content": {
                "kind": "metrics",
                "metrics": [
                    {
                        "key": "shardsSuccessful",
                        "label": "Shards successful",
                        "value": 4,
                    },
                    {
                        "key": "shardsTotal",
                        "label": "Shards total",
                        "value": 8,
                    },
                ],
            },
        }],
    }
    assert status.calls == [
        (node_id, "datasnapshots", "source-snapshot", True),
    ]


def test_runtime_status_rejects_nodes_without_a_resource_identity(tmp_path):
    status = _RuntimeStatus()
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        coordinator=_Coordinator(Observation(snapshot=_snapshot())),
        runtime_status=status,
    )

    with TestClient(app) as client:
        response = client.get(
            "/api/v1/nodes/resource:captureproxies:capture/runtime-status"
        )

    assert response.status_code == 404
    assert status.calls == []


class _Outputs:
    def __init__(self):
        self.target_id = None
        self.output_id = None
        self.descriptor = OutputDescriptor(
            id="managed-output:opaque",
            target_id=(
                "output:snapshotmigrations:migration-0:metadataEvaluate"
            ),
            resource_id="resource:snapshotmigrations:migration-0",
            resource_plural="snapshotmigrations",
            resource_name="migration-0",
            output_name="metadataEvaluate",
            stage="Evaluate",
            stage_order=0,
            attempt="migration",
            timestamp="2026-08-13T12:00:00Z",
            source="s3://outputs/evaluate.log",
            content_type="application/json",
        )

    def list_outputs(self, target_id):
        self.target_id = target_id
        return OutputInventory(
            target_id=target_id,
            resource_id=self.descriptor.resource_id,
            outputs=(self.descriptor,),
        )

    def read_output(self, output_id):
        self.output_id = output_id
        return OutputContent(
            descriptor=self.descriptor,
            content='{"valid":true}',
            inline=True,
            size=14,
        )

    def download_output(self, output_id):
        self.output_id = output_id
        return self.descriptor, b'{"valid":true}'


def test_output_routes_return_context_content_and_download(tmp_path):
    outputs = _Outputs()
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        outputs=outputs,
    )

    with TestClient(app) as client:
        inventory = client.get(
            "/api/v1/outputs",
            params={"targetId": outputs.descriptor.target_id},
        )
        content = client.get(
            "/api/v1/outputs/content",
            params={"outputId": outputs.descriptor.id},
        )
        download = client.get(
            "/api/v1/outputs/download",
            params={"outputId": outputs.descriptor.id},
        )

    assert inventory.status_code == 200
    assert inventory.json()["outputs"][0]["stage"] == "Evaluate"
    assert inventory.json()["outputs"][0]["source"] == (
        "s3://outputs/evaluate.log"
    )
    assert content.status_code == 200
    assert content.json()["content"] == '{"valid":true}'
    assert download.status_code == 200
    assert download.content == b'{"valid":true}'
    assert download.headers["content-disposition"].startswith("attachment;")
    assert outputs.target_id == outputs.descriptor.target_id
    assert outputs.output_id == outputs.descriptor.id


def test_output_route_distinguishes_stale_and_read_failures(tmp_path):
    outputs = _Outputs()
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        outputs=outputs,
    )

    outputs.read_output = lambda _output_id: (_ for _ in ()).throw(
        OutputStale("The output reference changed")
    )
    with TestClient(app) as client:
        stale = client.get(
            "/api/v1/outputs/content",
            params={"outputId": outputs.descriptor.id},
        )

    outputs.read_output = lambda _output_id: (_ for _ in ()).throw(
        OutputReadFailed("S3 is unavailable")
    )
    with TestClient(app) as client:
        failed = client.get(
            "/api/v1/outputs/content",
            params={"outputId": outputs.descriptor.id},
        )

    assert stale.status_code == 409
    assert stale.json()["detail"]["code"] == "output_stale"
    assert failed.status_code == 502
    assert failed.json()["detail"]["code"] == "output_read_failed"


class _Logs:
    def __init__(self):
        self.target = LogTarget(
            id="log-target-opaque",
            label="capture-0 / capture-proxy",
            kind="container",
            pod_name="capture-0",
            pod_uid="pod-uid",
            container="capture-proxy",
            restart_count=1,
            previous=False,
            supports_follow=True,
        )
        self.event = LogEvent(
            sequence=7,
            received_at="2026-08-13T20:00:01Z",
            timestamp="2026-08-13T20:00:00Z",
            pod_name="capture-0",
            pod_uid="pod-uid",
            container="capture-proxy",
            restart_count=1,
            previous=False,
            message="proxy is ready",
        )
        self.calls = []
        self.stopped = False

    def combine_targets(self, target_ids, label="All sources"):
        self.calls.append(("combine", tuple(target_ids), label))
        return LogTarget(
            id="log-target-all-sources",
            label=label,
            kind="aggregate",
            pod_name=None,
            pod_uid=None,
            container=None,
            restart_count=None,
            previous=False,
            supports_follow=True,
        )

    def list_targets(self, node_id, capability_target_id):
        self.calls.append(("targets", node_id, capability_target_id))
        return LogTargetInventory(
            node_id=node_id,
            capability_target_id=capability_target_id,
            targets=(self.target,),
        )

    def start(self, target_id, tail_lines, follow, page_size):
        self.calls.append((
            "start",
            target_id,
            tail_lines,
            follow,
            page_size,
        ))
        return LogStream(
            id="log-stream-opaque",
            target=self.target,
            state="following",
            page=self.page("log-stream-opaque"),
        )

    def page(self, stream_id, before=None, after=None, limit=200):
        self.calls.append(("page", stream_id, before, after, limit))
        return LogPage(
            events=(self.event,),
            before_cursor="before-7",
            after_cursor="after-7",
            at_available_start=True,
            at_buffer_end=True,
            history_truncated=False,
            state="following",
        )

    def stop(self, stream_id):
        self.stopped = True
        return LogStreamStatus(id=stream_id, state="stopped")

    def shutdown(self):
        self.stopped = True


def _log_snapshot():
    snapshot = _snapshot()
    node = snapshot.nodes["resource:captureproxies:capture"]
    node = ManageNode(
        **{
            **node.__dict__,
            "capabilities": (
                ManageCapability(
                    "logs",
                    "logs:captureproxies:capture",
                    "Logs for capture",
                ),
            ),
        }
    )
    return ManageSnapshot(
        **{
            **snapshot.__dict__,
            "nodes": {node.id: node},
        }
    )


def test_log_routes_use_node_capability_and_server_issued_targets(tmp_path):
    logs = _Logs()
    coordinator = _Coordinator(Observation(snapshot=_log_snapshot()))
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        coordinator=coordinator,
        logs=logs,
        settings=WebAppSettings(
            external_logs_url=(
                "https://console.aws.amazon.com/cloudwatch/home"
                "?region=us-east-2#logsV2:log-groups/log-group/"
                "$252Fmigration-assistant-dev-us-east-2$252Flogs"
            ),
        ),
    )

    with TestClient(app) as client:
        targets = client.get(
            "/api/v1/nodes/resource:captureproxies:capture/log-targets"
        )
        started = client.post(
            "/api/v1/log-streams",
            json={
                "targetId": "log-target-opaque",
                "tailLines": 500,
                "follow": True,
                "pageSize": 100,
            },
        )
        page = client.get(
            "/api/v1/log-streams/log-stream-opaque/pages",
            params={"before": "before-7", "limit": 50},
        )
        stopped = client.delete(
            "/api/v1/log-streams/log-stream-opaque"
        )

    assert targets.status_code == 200
    assert targets.json()["subjectLabel"] == "capture"
    assert targets.json()["subjectKind"] == "resource"
    assert targets.json()["externalLogsUrl"].endswith(
        "$252Fmigration-assistant-dev-us-east-2$252Flogs"
    )
    assert targets.json()["targets"][0]["podName"] == "capture-0"
    assert started.status_code == 201
    assert started.json()["page"]["events"][0]["message"] == "proxy is ready"
    assert page.status_code == 200
    assert stopped.json()["state"] == "stopped"
    assert logs.calls[0] == (
        "targets",
        "resource:captureproxies:capture",
        "logs:captureproxies:capture",
    )
    assert logs.calls[1] == (
        "start",
        "log-target-opaque",
        500,
        True,
        100,
    )
    assert logs.stopped is True


def test_resource_log_targets_list_failed_workflow_steps_first(tmp_path):
    logs = _Logs()
    snapshot = _log_snapshot()
    resource_id = "resource:captureproxies:capture"
    step_id = f"workflow-step:{resource_id}:wait-for-endpoint"
    resource = snapshot.nodes[resource_id]
    resource = ManageNode(
        **{
            **resource.__dict__,
            "status": "error",
            "child_ids": (step_id,),
        }
    )
    step = ManageNode(
        id=step_id,
        revision="step-revision",
        kind="workflow-step",
        label="waitForProxyEndpointReady",
        status="error",
        parent_id=resource_id,
        capabilities=(
            ManageCapability(
                "logs",
                "logs:workflow-step:wait-for-endpoint",
                "View logs",
            ),
        ),
    )
    snapshot = ManageSnapshot(
        **{
            **snapshot.__dict__,
            "nodes": {
                resource_id: resource,
                step_id: step,
            },
        }
    )
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        coordinator=_Coordinator(Observation(snapshot=snapshot)),
        logs=logs,
    )

    with TestClient(app) as client:
        response = client.get(
            "/api/v1/nodes/resource:captureproxies:capture/log-targets"
        )

    assert response.status_code == 200
    assert [target["label"] for target in response.json()["targets"]] == [
        "All sources",
        (
            "Failure: waitForProxyEndpointReady / "
            "capture-0 / capture-proxy"
        ),
        "Application / capture-0 / capture-proxy",
    ]
    assert response.json()["message"] == (
        "Workflow-step and application containers are shown together by "
        "default. Select a target to narrow the view."
    )
    assert logs.calls[:3] == [
        (
            "targets",
            resource_id,
            "logs:captureproxies:capture",
        ),
        (
            "targets",
            step_id,
            "logs:workflow-step:wait-for-endpoint",
        ),
        (
            "combine",
            ("log-target-opaque", "log-target-opaque"),
            "All sources",
        ),
    ]


def test_log_targets_require_a_capability_on_the_observed_node(tmp_path):
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        coordinator=_Coordinator(Observation(snapshot=_snapshot())),
        logs=_Logs(),
    )

    with TestClient(app) as client:
        response = client.get(
            "/api/v1/nodes/resource:captureproxies:capture/log-targets"
        )

    assert response.status_code == 404
    assert response.json()["detail"]["code"] == "logs_unavailable"


class _Approvals:
    def __init__(self):
        self.approved = False
        self.preapproval = None
        self.review_result = ApprovalReview(
            target_id="approval:node-1",
            node_id="node-1",
            gate_name="evaluatemetadata.source-target-snapshot-main",
            gate_revision="11",
            workflow_name="migration",
            resource_id="resource:snapshotmigrations:migration-0",
            resource_kind="SnapshotMigration",
            resource_name="migration-0",
            stage="Metadata evaluation",
            effect=(
                "Approving allows metadata evaluation to complete and "
                "advances to metadata migration."
            ),
            reason=None,
            snapshot_revision="snapshot-revision",
        )
        self.inventory_result = ApprovalGateInventory(
            workflow_name="migration",
            gates=(
                ApprovalGateSummary(
                    name="migratemetadata.migration-0",
                    gate_revision="12",
                    category="checkpoint",
                    state="upcoming",
                    phase="Created",
                    resource_id=(
                        "resource:snapshotmigrations:migration-0"
                    ),
                    resource_kind="SnapshotMigration",
                    resource_name="migration-0",
                    stage="Metadata migration",
                    effect="Approving advances metadata migration.",
                    reason=None,
                    enabled=True,
                    approved=False,
                    toggleable=True,
                    disabled_reason=None,
                    approval_target_id=None,
                    output_target_id=None,
                ),
            ),
        )

    def review(self, target_id, snapshot_revision=None):
        assert target_id == self.review_result.target_id
        return self.review_result

    def validate(self, target_id, expected_gate_revision):
        assert target_id == self.review_result.target_id
        assert expected_gate_revision == self.review_result.gate_revision
        return self.review_result

    def approve(self, target_id, expected_gate_revision):
        self.approved = True
        return self.validate(target_id, expected_gate_revision)

    def inventory(self):
        return self.inventory_result

    def set_preapproval(
        self,
        gate_name,
        expected_gate_revision,
        preapproved,
    ):
        assert gate_name == self.inventory_result.gates[0].name
        assert expected_gate_revision == "12"
        self.preapproval = preapproved
        return self.inventory_result.gates[0]


class _Resets:
    def __init__(self):
        self.executed = False
        self.planned_target_ids = None
        self.plan_result = ResetPlan(
            token="reset-token",
            request_target_id="reset:snapshotmigrations:migration-0",
            targets=(
                ResetTarget(
                    plural="snapshotmigrations",
                    type="snapshotmigration",
                    name="migration-0",
                    path="snapshotmigration.migration-0",
                    phase="Ready",
                    depends_on=(),
                    uid="resource-uid",
                    resource_version="10",
                ),
            ),
            messages=(),
            warnings=("Target indexes are retained.",),
        )

    def plan(self, target_id):
        assert target_id == self.plan_result.request_target_id
        return self.plan_result

    def plan_many(self, target_ids):
        self.planned_target_ids = list(target_ids)
        return self.plan_result

    def validate(self, token):
        if token != self.plan_result.token:
            raise ResetPlanStale("Plan changed")
        return self.plan_result

    def execute(self, token):
        self.validate(token)
        self.executed = True
        return ResetExecutionResult(
            plan=self.plan_result,
            message="Reset completed for 1 resource",
            detail="Deleted snapshotmigration.migration-0",
        )


def test_approval_and_reset_routes_review_exact_targets_then_track_work(
    tmp_path,
):
    approvals = _Approvals()
    resets = _Resets()
    operations = _Operations()
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        approvals=approvals,
        resets=resets,
        operations=operations,
    )

    with TestClient(app) as client:
        approval_gates = client.get("/api/v1/approval-gates")
        preapproval = client.patch(
            "/api/v1/approval-gates/migratemetadata.migration-0",
            json={
                "expectedGateRevision": "12",
                "preapproved": True,
            },
        )
        approval_review = client.get(
            "/api/v1/approvals/review",
            params={"targetId": "approval:node-1"},
        )
        approval = client.post(
            "/api/v1/approvals",
            json={
                "targetId": "approval:node-1",
                "expectedGateRevision": "11",
            },
        )
        approval_worker = operations.started["worker"]
        reset_plan = client.post(
            "/api/v1/resets/plan",
            json={
                "targetId": "reset:snapshotmigrations:migration-0",
            },
        )
        combined_reset_plan = client.post(
            "/api/v1/resets/plan",
            json={
                "targetIds": [
                    "reset:snapshotmigrations:migration-0",
                    "reset:datasnapshots:snapshot-0",
                ],
            },
        )
        reset = client.post(
            "/api/v1/resets",
            json={"planToken": "reset-token"},
        )
        stale = client.post(
            "/api/v1/resets",
            json={"planToken": "stale-token"},
        )

    assert approval_gates.status_code == 200
    assert approval_gates.json()["gates"][0]["state"] == "upcoming"
    assert preapproval.status_code == 200
    assert preapproval.json() == {
        "gateName": "migratemetadata.migration-0",
        "preapproved": True,
    }
    assert approvals.preapproval is True
    assert approval_review.status_code == 200
    assert approval_review.json()["stage"] == "Metadata evaluation"
    assert approval.status_code == 202
    approval_result = approval_worker()
    assert approval_result.waiting is True
    assert approvals.approved is True

    assert reset_plan.status_code == 200
    assert combined_reset_plan.status_code == 200
    assert resets.planned_target_ids == [
        "reset:snapshotmigrations:migration-0",
        "reset:datasnapshots:snapshot-0",
    ]
    assert reset_plan.json()["targets"][0]["name"] == "migration-0"
    assert "resourceVersion" not in reset_plan.text
    assert reset.status_code == 202
    reset_result = operations.started["worker"]()
    assert reset_result.waiting is False
    assert resets.executed is True
    assert stale.status_code == 409
    assert stale.json()["detail"]["code"] == "reset_plan_stale"


def test_vap_reset_saves_then_submits_a_new_workflow_without_approving_old_gate(
    tmp_path,
):
    approvals = _Approvals()
    resets = _Resets()
    operations = _Operations()
    documents = _Documents()
    submissions = _Submissions(documents)
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        approvals=approvals,
        resets=resets,
        operations=operations,
        config_documents=submissions,
        settings=WebAppSettings(workflow_name="migration-test"),
    )

    with TestClient(app) as client:
        response = client.post(
            "/api/v1/resets",
            json={
                "planToken": "reset-token",
                "resubmit": True,
                "expectedPersistedRevision": "11",
            },
        )
        worker = operations.started["worker"]

    assert response.status_code == 202
    assert operations.started["label"] == (
        "Reset and resubmit snapshotmigration.migration-0"
    )
    assert [item.persisted_revision for item in submissions.prepared] == ["11"]
    result = worker()
    assert resets.executed is True
    assert approvals.approved is False
    assert [item.persisted_revision for item in submissions.prepared] == [
        "11",
        "11",
    ]
    assert submissions.submitted[1] == "migration-test"
    assert result.waiting is True
    assert result.result["workflowName"] == "migration-test"
    assert "replacement workflow" in result.message


def test_reset_worker_rechecks_saved_revision_before_deleting_resources(
    tmp_path,
):
    resets = _Resets()
    operations = _Operations()
    documents = _Documents()
    submissions = _Submissions(documents)
    submissions.fail_worker_revision = True
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        resets=resets,
        operations=operations,
        config_documents=submissions,
    )

    with TestClient(app) as client:
        response = client.post(
            "/api/v1/resets",
            json={
                "planToken": "reset-token",
                "resubmit": True,
                "expectedPersistedRevision": "11",
            },
        )
        worker = operations.started["worker"]

    assert response.status_code == 202
    with pytest.raises(ConfigurationDocumentConflict):
        worker()
    assert resets.executed is False
    assert submissions.submitted is None


class _ExternalResources:
    def __init__(self):
        self.selection = None
        self.external_read = None
        self.external_save = None

    def list(self, raw_yaml, node_id):
        self.raw_yaml = raw_yaml
        return ExternalResourceInventory(
            node_id=node_id,
            display_name="Transform ConfigMap",
            rows=[{
                "name": "transform",
                "kind": "ConfigMap",
                "group": "",
                "version": "v1",
                "keys": ["main.js", "settings.json"],
                "status": "matching",
                "message": "",
                "current": True,
            }],
        )

    def validate_selection(self, **selection):
        self.selection = selection

    def read(self, raw_yaml, node_id, name):
        self.external_read = {
            "raw_yaml": raw_yaml,
            "node_id": node_id,
            "name": name,
        }
        return ExternalResourceDetails(
            node_id=node_id,
            display_name="HTTP Basic Auth Secret",
            name=name,
            kind="Secret",
            resource_type="kubernetes.io/basic-auth",
            keys=["password", "username"],
            field_values={
                "secretName": name,
                "username": "admin",
            },
            hidden_fields=["password"],
            missing=False,
            message=None,
        )

    def save(self, **request):
        self.external_save = request
        return ExternalResourceMutation(
            name="next-creds",
            kind="Secret",
            message="Secret updated: next-creds",
        )


class _Documents:
    def __init__(self):
        self.current = ConfigurationDocument(
            raw_yaml="sourceClusters: {}\n",
            persisted_revision="11",
        )
        self.saved = None

    def load(self):
        return self.current

    def save(self, expected_persisted_revision, raw_yaml):
        self.saved = (expected_persisted_revision, raw_yaml)
        self.current = ConfigurationDocument(
            raw_yaml=raw_yaml,
            persisted_revision="12",
        )
        return self.current


class _Submissions:
    def __init__(self, documents):
        self.documents = documents
        self.prepared = []
        self.preflight_revision = None
        self.review_revision = None
        self.submitted = None
        self.fail_worker_revision = False

    def review(self, expected_revision, snapshot=None):
        self.review_revision = expected_revision
        self._require_revision(expected_revision)
        return SavedConfigReview(
            persisted_revision=expected_revision,
            valid=True,
            validation_messages=(),
            changes=(
                ConfigReviewChange(
                    resource_id="resource:trafficproxies:capture",
                    resource_label="capture",
                    path="traffic.proxies.capture.serviceType",
                    label="Service type",
                    kind="field",
                ),
            ),
        )

    def prepare(self, expected_revision):
        if self.fail_worker_revision and self.prepared:
            self.documents.current = ConfigurationDocument(
                raw_yaml="targetClusters: {}\n",
                persisted_revision="12",
            )
        self._require_revision(expected_revision)
        prepared = PreparedConfigSubmission(
            persisted_revision=expected_revision,
            raw_yaml=self.documents.current.raw_yaml,
        )
        self.prepared.append(prepared)
        return prepared

    def preflight(self, expected_revision, workflow_name):
        self._require_revision(expected_revision)
        self.preflight_revision = expected_revision
        return AdmissionPreflightReport(
            checked_resources=2,
            deployment_actions=(
                AdmissionDeploymentAction(
                    kind="CaptureProxy",
                    name="capture",
                    plural="captureproxies",
                    action="reconcile",
                    reason="checksum-only",
                    message=(
                        "The generated checksum changed, although no "
                        "projected fields changed."
                    ),
                    current_config_checksum="old",
                    desired_config_checksum="new",
                ),
            ),
            issues=(
                AdmissionPreflightIssue(
                    kind="CapturedTraffic",
                    name="capture-topic",
                    plural="capturedtraffics",
                    classification="recreate-required",
                    message="sourceLabel cannot be changed",
                    source="kubernetes",
                ),
                AdmissionPreflightIssue(
                    kind="TrafficReplay",
                    name="replay",
                    plural="trafficreplays",
                    classification="approval-required",
                    message="tupleMaxFileSizeMb requires approval",
                    source="kubernetes",
                ),
            ),
        )

    def submit(self, prepared, workflow_name):
        self.submitted = (prepared, workflow_name)
        return {"workflow_name": workflow_name}

    def _require_revision(self, expected_revision):
        if self.documents.current.persisted_revision != expected_revision:
            raise ConfigurationDocumentConflict(self.documents.current)


class _ConfigDiagnostics:
    def __init__(self):
        self.raw_yaml = None

    def diagnose_external_resources(self, raw_yaml):
        self.raw_yaml = raw_yaml
        return {
            "status": "error",
            "diagnostics": [{
                "severity": "error",
                "message": "Secret 'missing' was not found.",
                "path": ["sourceClusters", "source", "authConfig", "basic", "secretName"],
            }],
        }


def test_config_diagnostics_echo_the_browser_nonce(tmp_path):
    diagnostics = _ConfigDiagnostics()
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        config_diagnostics=diagnostics,
    )

    with TestClient(app) as client:
        response = client.post(
            "/api/v1/config/diagnostics",
            json={
                "rawYaml": "sourceClusters: {}\n",
                "draftNonce": "browser:12:7",
            },
        )

    assert response.status_code == 200
    assert response.json() == {
        "draftNonce": "browser:12:7",
        "status": "error",
        "diagnostics": [{
            "severity": "error",
            "message": "Secret 'missing' was not found.",
            "path": ["sourceClusters", "source", "authConfig", "basic", "secretName"],
        }],
    }
    assert diagnostics.raw_yaml == "sourceClusters: {}\n"


class _Connectivity:
    def __init__(self):
        self.prepared = None
        self.ran = None

    def prepare(self, raw_yaml, config_nonce):
        self.prepared = (raw_yaml, config_nonce)
        return PreparedConnectivityChecks(
            config_nonce=config_nonce,
            targets=(
                ConnectivityTarget(
                    id="source:source",
                    kind="source",
                    ref_name="source",
                    label="Source source",
                    edit_path=("sourceClusters", "source"),
                    client_config={"endpoint": "https://source.example"},
                ),
            ),
        )

    def run(self, prepared, target_ids):
        self.ran = (prepared, target_ids)
        return {
            "configNonce": prepared.config_nonce,
            "status": "valid",
            "summary": "All 1 applicable connectivity checks passed.",
            "checks": [],
        }


def test_connectivity_inventory_and_checks_use_strict_draft_nonce(tmp_path):
    connectivity = _Connectivity()
    operations = _Operations()
    operations.operation = Operation(
        id="operation-connectivity",
        kind="connectivity-check",
        label="Check source:source",
        status="queued",
        target_ids=("source:source",),
        created_at="2026-09-16T13:00:00Z",
        updated_at="2026-09-16T13:00:00Z",
        message="Queued",
    )
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        connectivity=connectivity,
        operations=operations,
    )

    with TestClient(app) as client:
        inventory = client.post(
            "/api/v1/config/connectivity/inventory",
            json={"rawYaml": "sourceClusters: {}\n", "configNonce": "nonce-1"},
        )
        started = client.post(
            "/api/v1/config/connectivity/checks",
            json={
                "rawYaml": "sourceClusters: {}\n",
                "configNonce": "nonce-1",
                "targetIds": ["source:source"],
            },
        )

    assert inventory.status_code == 200
    assert inventory.json() == {
        "configNonce": "nonce-1",
        "targets": [{
            "id": "source:source",
            "kind": "source",
            "refName": "source",
            "label": "Source source",
            "editPath": ["sourceClusters", "source"],
        }],
    }
    assert started.status_code == 202
    assert operations.started["kind"] == "connectivity-check"
    result = operations.started["worker"]()
    assert result.result["configNonce"] == "nonce-1"
    assert connectivity.ran[1] == ["source:source"]


def test_config_document_load_and_save_are_revisioned_and_invalidate_state(
    tmp_path,
):
    documents = _Documents()
    coordinator = _Coordinator(Observation(snapshot=_snapshot()))
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        config_documents=documents,
        coordinator=coordinator,
    )

    with TestClient(app) as client:
        loaded = client.get("/api/v1/config/document")
        saved = client.put(
            "/api/v1/config/document",
            json={
                "expectedPersistedRevision": "11",
                "rawYaml": "targetClusters: {}\n",
            },
        )

    assert loaded.status_code == 200
    assert loaded.json() == {
        "rawYaml": "sourceClusters: {}\n",
        "persistedRevision": "11",
        "modelVersion": "1",
    }
    assert saved.status_code == 200
    assert saved.json()["persistedRevision"] == "12"
    assert documents.saved == ("11", "targetClusters: {}\n")
    assert coordinator.config_invalidations == ["12"]


def test_config_schema_exposes_the_cluster_enriched_editor_schema(tmp_path):
    schema_path = tmp_path / "workflowMigration.schema.json"
    schema_path.write_text(
        json.dumps({
            "$id": "workflowMigration.schema.json",
            "type": "object",
            "properties": {"traffic": {"type": "object"}},
        }),
        encoding="utf-8",
    )
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        config_schema_path=schema_path,
    )

    with TestClient(app) as client:
        response = client.get("/api/v1/config/schema")

    assert response.status_code == 200
    assert response.json() == {
        "unifiedSchema": {
            "$id": "workflowMigration.schema.json",
            "type": "object",
            "properties": {"traffic": {"type": "object"}},
        }
    }


def test_config_document_conflict_returns_the_current_saved_document(tmp_path):
    documents = _Documents()

    def conflict(_expected_revision, _raw_yaml):
        raise ConfigurationDocumentConflict(documents.current)

    documents.save = conflict
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        config_documents=documents,
    )

    with TestClient(app) as client:
        response = client.put(
            "/api/v1/config/document",
            json={
                "expectedPersistedRevision": "10",
                "rawYaml": "targetClusters: {}\n",
            },
        )

    assert response.status_code == 409
    assert response.json()["detail"] == {
        "code": "persisted_revision_conflict",
        "message": (
            "The saved workflow configuration changed; reload it before saving."
        ),
        "current": {
            "rawYaml": "sourceClusters: {}\n",
            "persistedRevision": "11",
            "modelVersion": "1",
        },
    }


def test_config_document_validation_failure_is_actionable(tmp_path):
    documents = _Documents()

    def reject(_expected_revision, _raw_yaml):
        raise ValueError("Configuration validation failed: invalid source")

    documents.save = reject
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        config_documents=documents,
    )

    with TestClient(app) as client:
        response = client.put(
            "/api/v1/config/document",
            json={
                "expectedPersistedRevision": "11",
                "rawYaml": "sourceClusters: [\n",
            },
        )

    assert response.status_code == 422
    assert response.json()["detail"] == {
        "code": "configuration_document_invalid",
        "message": "Configuration validation failed: invalid source",
    }


class _Operations:
    def __init__(self):
        self.started = None
        self.operation = Operation(
            id="operation-submit",
            kind="submit",
            label="Submit workflow configuration",
            status="queued",
            target_ids=(),
            created_at="2026-08-13T13:00:00Z",
            updated_at="2026-08-13T13:00:00Z",
            message="Queued",
        )

    def start(self, **request):
        self.started = request
        return self.operation

    def list(self):
        return (self.operation,)

    def events_after(self, _cursor):
        return (
            OperationEvent(
                id=1,
                operation_id=self.operation.id,
                operation=self.operation,
            ),
        )

    def reconcile_submit(self, **_state):
        return ()


def test_config_review_and_submit_start_a_tracked_operation(tmp_path):
    documents = _Documents()
    submissions = _Submissions(documents)
    operations = _Operations()
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        config_documents=submissions,
        operations=operations,
        settings=WebAppSettings(workflow_name="migration-test"),
    )

    with TestClient(app) as client:
        review = client.post(
            "/api/v1/config/review",
            json={"expectedPersistedRevision": "11"},
        )
        response = client.post(
            "/api/v1/config/submit",
            json={"expectedPersistedRevision": "11"},
        )

    assert review.status_code == 200
    assert review.json()["persistedRevision"] == "11"
    assert review.json()["valid"] is True
    assert review.json()["changes"][0]["resourceLabel"] == "capture"
    assert response.status_code == 202
    assert response.json()["id"] == "operation-submit"
    assert [item.persisted_revision for item in submissions.prepared] == ["11"]
    assert submissions.submitted is None
    assert operations.started["kind"] == "submit"
    result = operations.started["worker"]()
    assert result.waiting is True
    assert result.result["workflowName"] == "migration-test"
    assert [item.persisted_revision for item in submissions.prepared] == [
        "11",
        "11",
    ]
    assert submissions.submitted[1] == "migration-test"


def test_submit_worker_rejects_a_saved_configuration_changed_after_acceptance(
    tmp_path,
):
    documents = _Documents()
    submissions = _Submissions(documents)
    submissions.fail_worker_revision = True
    operations = _Operations()
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        config_documents=submissions,
        operations=operations,
    )

    with TestClient(app) as client:
        response = client.post(
            "/api/v1/config/submit",
            json={"expectedPersistedRevision": "11"},
        )
        worker = operations.started["worker"]

    assert response.status_code == 202
    with pytest.raises(ConfigurationDocumentConflict):
        worker()
    assert submissions.submitted is None


def test_config_preflight_reports_blocking_and_nonblocking_admission_results(
    tmp_path,
):
    documents = _Documents()
    submissions = _Submissions(documents)
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        config_documents=submissions,
        settings=WebAppSettings(workflow_name="migration-test"),
    )

    with TestClient(app) as client:
        response = client.post(
            "/api/v1/config/preflight",
            json={"expectedPersistedRevision": "11"},
        )

    assert response.status_code == 200
    assert response.json() == {
        "checkedResources": 2,
        "allowed": False,
        "deploymentActions": [
            {
                "kind": "CaptureProxy",
                "name": "capture",
                "plural": "captureproxies",
                "action": "reconcile",
                "reason": "checksum-only",
                "message": (
                    "The generated checksum changed, although no projected "
                    "fields changed."
                ),
                "resourceId": "resource:captureproxies:capture",
                "currentConfigChecksum": "old",
                "desiredConfigChecksum": "new",
            },
        ],
        "issues": [
            {
                "kind": "CapturedTraffic",
                "name": "capture-topic",
                "plural": "capturedtraffics",
                "classification": "recreate-required",
                "message": "sourceLabel cannot be changed",
                "source": "kubernetes",
                "blocking": True,
                "resourceId": "resource:capturedtraffics:capture-topic",
                "resetTargetId": "reset:capturedtraffics:capture-topic",
            },
            {
                "kind": "TrafficReplay",
                "name": "replay",
                "plural": "trafficreplays",
                "classification": "approval-required",
                "message": "tupleMaxFileSizeMb requires approval",
                "source": "kubernetes",
                "blocking": False,
                "resourceId": "resource:trafficreplays:replay",
            },
        ],
    }


def test_config_preflight_reports_preparation_failures_without_plain_500(
    tmp_path,
):
    documents = _Documents()
    submissions = _Submissions(documents)

    def fail_preflight(expected_revision, workflow_name):
        raise RuntimeError(
            "Workflow submission preparation failed with exit code 1\n"
            "Error: getaddrinfo ENOTFOUND localstack"
        )

    submissions.preflight = fail_preflight
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        config_documents=submissions,
        settings=WebAppSettings(workflow_name="migration-test"),
    )

    with TestClient(app) as client:
        response = client.post(
            "/api/v1/config/preflight",
            json={"expectedPersistedRevision": "11"},
        )

    assert response.status_code == 502
    assert response.json() == {
        "detail": {
            "code": "admission_preflight_unavailable",
            "message": (
                "Admission preflight could not prepare the workflow: "
                "Workflow submission preparation failed with exit code 1\n"
                "Error: getaddrinfo ENOTFOUND localstack"
            ),
        },
    }


def test_external_routes_return_keys_and_submit_exact_selection(tmp_path):
    resources = _ExternalResources()
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        external_resources=resources,
    )

    with TestClient(app) as client:
        inventory = client.post(
            "/api/v1/external-resources",
            json={
                "rawYaml": "traffic: {}\n",
                "nodeId": "edit:traffic.transform.configMap",
            },
        )
        selected = client.post(
            "/api/v1/external-resources/select",
            json={
                "rawYaml": "traffic: {}\n",
                "nodeId": "edit:traffic.transform.configMap",
                "name": "transform",
                "kind": "ConfigMap",
                "group": "",
                "key": "main.js",
                "acceptWarning": False,
                "manual": True,
            },
        )

    assert inventory.status_code == 200
    assert inventory.json()["rows"][0]["keys"] == ["main.js", "settings.json"]
    assert "values" not in inventory.json()["rows"][0]
    assert selected.status_code == 200
    assert selected.json() == {"accepted": True}
    assert resources.selection == {
        "raw_yaml": "traffic: {}\n",
        "node_id": "edit:traffic.transform.configMap",
        "name": "transform",
        "kind": "ConfigMap",
        "group": "",
        "key": "main.js",
        "accept_warning": False,
        "manual": True,
    }


def test_external_detail_and_save_routes_never_return_secret_values(tmp_path):
    resources = _ExternalResources()
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        external_resources=resources,
    )

    with TestClient(app) as client:
        details = client.post(
            "/api/v1/external-resources/details",
            json={
                "rawYaml": "sourceClusters: {}\n",
                "nodeId": "edit:source.auth.secretName",
                "name": "source-creds",
            },
        )
        saved = client.post(
            "/api/v1/external-resources/save",
            json={
                "rawYaml": "sourceClusters: {}\n",
                "nodeId": "edit:source.auth.secretName",
                "values": {
                    "secretName": "next-creds",
                    "username": "root",
                    "password": "",
                },
                "confirmations": {"password": ""},
                "existingName": "source-creds",
            },
        )

    assert details.status_code == 200
    assert details.json()["fieldValues"] == {
        "secretName": "source-creds",
        "username": "admin",
    }
    assert details.json()["hiddenFields"] == ["password"]
    assert "values" not in details.json()
    assert "password" not in json.dumps(details.json()["fieldValues"])
    assert resources.external_read == {
        "raw_yaml": "sourceClusters: {}\n",
        "node_id": "edit:source.auth.secretName",
        "name": "source-creds",
    }

    assert saved.status_code == 200
    assert saved.json()["name"] == "next-creds"
    assert saved.json()["message"] == "Secret updated: next-creds"
    assert "draft" not in saved.json()
    assert resources.external_save == {
        "raw_yaml": "sourceClusters: {}\n",
        "node_id": "edit:source.auth.secretName",
        "values": {
            "secretName": "next-creds",
            "username": "root",
            "password": "",
        },
        "confirmations": {"password": ""},
        "existing_name": "source-creds",
    }


def test_external_routes_without_a_resource_service_are_unavailable(tmp_path):
    app = create_app(static_dir=_static_bundle(tmp_path))

    with TestClient(app) as client:
        response = client.post(
            "/api/v1/external-resources",
            json={
                "rawYaml": "traffic: {}\n",
                "nodeId": "edit:traffic.transform.configMap",
            },
        )

    assert response.status_code == 503
    assert response.json()["detail"] == (
        "External resource access is not configured"
    )

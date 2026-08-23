import json
from datetime import datetime, timezone
from fastapi.testclient import TestClient

from console_link.workflow.application.config_drafts import (
    ConfigDraft,
    ConfigDraftConflict,
    ConfigRemovalImpact,
    ConfigRemovalImpactEntry,
    ConfigSubmission,
    ExternalResourceDetails,
    ExternalResourceInventory,
    ExternalResourceMutation,
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
from console_link.workflow.application.actions import ApprovalReview
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
from console_link.workflow.web.app import create_app
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
    assert schemas["EditInputHintV1"]["properties"][
        "resourceCollection"
    ]["anyOf"][0] == {
        "$ref": "#/components/schemas/ResourceCollectionHintV1",
    }
    assert schemas["EditInputHintV1"]["properties"][
        "definitionCollection"
    ]["anyOf"][0] == {
        "$ref": "#/components/schemas/DefinitionCollectionHintV1",
    }
    assert schemas["EditNodeV1"]["properties"]["referenceTargetId"] == {
        "anyOf": [
            {"type": "string"},
            {"type": "null"},
        ],
        "title": "Referencetargetid",
    }


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
    drafts = _Drafts()
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        approvals=approvals,
        resets=resets,
        operations=operations,
        config_drafts=drafts,
        workflow_name="migration-test",
    )

    with TestClient(app) as client:
        response = client.post(
            "/api/v1/resets",
            json={
                "planToken": "reset-token",
                "resubmit": True,
                "expectedDraftRevision": "draft-1",
            },
        )
        worker = operations.started["worker"]

    assert response.status_code == 202
    assert operations.started["label"] == (
        "Reset and resubmit snapshotmigration.migration-0"
    )
    assert drafts.prepared is True
    assert drafts.expected_revision == "draft-1"
    result = worker()
    assert resets.executed is True
    assert approvals.approved is False
    assert drafts.submitted is True
    assert result.waiting is True
    assert result.result["workflowName"] == "migration-test"
    assert "replacement workflow" in result.message


def _edit_state():
    return {
        "formatVersion": 1,
        "provenance": {
            "source": "pending-yaml",
            "lossy": False,
            "warnings": [],
        },
        "nodes": [{
            "id": "edit:traffic",
            "path": ["traffic"],
            "label": "Traffic",
            "valueKind": "object",
            "presence": "optional",
            "expert": False,
            "essential": True,
            "status": "warning",
            "statusCounts": {"warnings": 1},
            "diagnostics": [{
                "severity": "warning",
                "message": "Check this branch",
                "path": ["traffic"],
            }],
            "children": [{
                "id": "edit:traffic.enabled",
                "path": ["traffic", "enabled"],
                "label": "enabled: true",
                "value": True,
                "valueAuthored": True,
                "valueType": "boolean",
                "valueKind": "boolean",
                "status": "ok",
                "children": [],
            }],
        }],
        "validation": {
            "valid": True,
            "errors": [],
            "diagnostics": [],
        },
    }


class _Drafts:
    def __init__(self):
        self.current = ConfigDraft(
            base_revision="base-1",
            draft_revision="draft-1",
            dirty=False,
            edit_state=_edit_state(),
        )
        self.operation = None
        self.expected_revision = None
        self.saved = False
        self.discarded = False
        self.submitted = False
        self.prepared = False
        self.selection = None
        self.external_read = None
        self.external_save = None

    def open(self):
        return self.current

    def apply(self, expected_revision, operation):
        self.expected_revision = expected_revision
        self.operation = operation
        return self.current

    def replace_raw(self, expected_revision, raw_yaml):
        self.expected_revision = expected_revision
        self.raw_yaml = raw_yaml
        return self.current

    def save(self, expected_revision):
        self.expected_revision = expected_revision
        self.saved = True
        return self.current

    def discard(self, expected_revision):
        self.expected_revision = expected_revision
        self.discarded = True
        return self.current

    def close(self, expected_revision):
        self.expected_revision = expected_revision
        self.closed = True

    def submit(self, expected_revision, workflow_name):
        self.expected_revision = expected_revision
        self.submitted = True
        return ConfigSubmission(
            draft=self.current,
            workflow_name=workflow_name,
            message=f"Workflow submitted: {workflow_name}",
        )

    def review(self, expected_revision, snapshot=None):
        self.expected_revision = expected_revision
        return {
            "draft_revision": self.current.draft_revision,
            "base_revision": self.current.base_revision,
            "dirty": self.current.dirty,
            "valid": True,
            "validation_messages": (),
            "changes": ({
                "resource_id": "resource:trafficproxies:capture",
                "resource_label": "capture",
                "path": "traffic.proxies.capture.serviceType",
                "label": "Service type",
                "kind": "field",
            },),
        }

    def prepare_submit(self, expected_revision):
        self.expected_revision = expected_revision
        self.prepared = True
        return self.current

    def preflight(self, expected_revision, workflow_name):
        self.expected_revision = expected_revision
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

    def submit_saved(self, workflow_name):
        self.submitted = True
        return {"workflow_name": workflow_name}

    def removal_impact(self, expected_revision, path):
        self.expected_revision = expected_revision
        return ConfigRemovalImpact(
            target_path=tuple(path),
            target_label=str(path[-1]),
            affected=(
                ConfigRemovalImpactEntry(
                    path=("traffic", "proxies", "capture"),
                    field_path=("traffic", "proxies", "capture", "source"),
                    reason="source=source",
                ),
            ),
        )

    def list_external_resources(self, expected_revision, node_id):
        self.expected_revision = expected_revision
        return ExternalResourceInventory(
            node_id=node_id,
            draft_revision=self.current.draft_revision,
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

    def select_external_resource(self, **selection):
        self.selection = selection
        return self.current

    def read_external_resource(self, expected_revision, node_id, name):
        self.external_read = {
            "expected_revision": expected_revision,
            "node_id": node_id,
            "name": name,
        }
        return ExternalResourceDetails(
            node_id=node_id,
            draft_revision=self.current.draft_revision,
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

    def save_external_resource(self, **request):
        self.external_save = request
        return ExternalResourceMutation(
            draft=self.current,
            name="next-creds",
            kind="Secret",
            message="Secret updated: next-creds",
        )


def test_config_routes_expose_recursive_edit_state_without_raw_yaml(tmp_path):
    drafts = _Drafts()
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        config_drafts=drafts,
    )

    with TestClient(app) as client:
        response = client.get("/api/v1/config")

    assert response.status_code == 200
    payload = response.json()
    assert payload["baseRevision"] == "base-1"
    assert payload["draftRevision"] == "draft-1"
    assert payload["dirty"] is False
    assert payload["editState"]["nodes"][0]["children"][0]["value"] is True
    assert payload["editState"]["nodes"][0]["essential"] is True
    assert "rawYaml" not in payload


def test_config_routes_include_server_projected_navigation(tmp_path):
    drafts = _Drafts()
    state = _edit_state()
    state["nodes"] = [{
        "id": "edit:sourceClusters",
        "path": ["sourceClusters"],
        "label": "Source clusters",
        "valueKind": "record",
        "status": "ok",
        "inputHint": {
            "kind": "record",
            "resourceCollection": {
                "navigation": {
                    "sectionId": "section:Sources",
                    "sectionLabel": "Sources",
                    "sectionOrder": 0,
                    "groupId": "group:Sources:Sources",
                    "groupLabel": "Sources",
                    "groupOrder": 0,
                },
                "resource": {
                    "kind": "SourceConfig",
                    "plural": "sourceconfigs",
                    "typeLabel": "Source cluster",
                    "identity": {"kind": "named"},
                },
            },
        },
        "diagnostics": [],
        "children": [{
            "id": "edit:sourceClusters.modern",
            "path": ["sourceClusters", "modern"],
            "label": "modern",
            "valueKind": "object",
            "status": "ok",
            "diagnostics": [],
            "children": [],
        }],
    }]
    drafts.current = ConfigDraft(
        base_revision="base-1",
        draft_revision="draft-2",
        dirty=True,
        edit_state=state,
    )
    coordinator = _Coordinator(Observation(snapshot=_snapshot()))
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        config_drafts=drafts,
        coordinator=coordinator,
    )

    with TestClient(app) as client:
        response = client.get("/api/v1/config")

    assert response.status_code == 200
    navigation = response.json()["navigation"]
    assert navigation["rootIds"][0] == "section:Sources"
    assert navigation["nodes"]["resource:sourceconfigs:modern"][
        "resourceType"
    ] == "Source cluster"
    assert navigation["nodes"]["resource:sourceconfigs:modern"][
        "configState"
    ] == {
        "validationErrors": 0,
        "validationWarnings": 0,
        "draftChangeCount": 0,
    }


def test_config_open_returns_actionable_service_error(tmp_path):
    drafts = _Drafts()

    def fail_to_open():
        raise RuntimeError("CONFIG_PROCESSOR_DIR is not configured")

    drafts.open = fail_to_open
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        config_drafts=drafts,
    )

    with TestClient(app, raise_server_exceptions=False) as client:
        response = client.get("/api/v1/config")

    assert response.status_code == 503
    assert response.json() == {
        "detail": {
            "code": "configuration_unavailable",
            "message": "CONFIG_PROCESSOR_DIR is not configured",
        }
    }


def test_config_operation_contract_is_discriminated_and_passed_to_the_service(tmp_path):
    drafts = _Drafts()
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        config_drafts=drafts,
    )

    with TestClient(app) as client:
        response = client.post(
            "/api/v1/config/operations",
            json={
                "expectedDraftRevision": "draft-1",
                "operation": {
                    "op": "renameConfig",
                    "path": ["traffic", "old"],
                    "newName": "next",
                },
            },
        )

    assert response.status_code == 200
    assert drafts.expected_revision == "draft-1"
    assert drafts.operation == {
        "op": "renameConfig",
        "path": ["traffic", "old"],
        "newName": "next",
    }


def test_raw_config_repair_is_revisioned_and_passed_to_the_service(tmp_path):
    drafts = _Drafts()
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        config_drafts=drafts,
    )

    with TestClient(app) as client:
        response = client.put(
            "/api/v1/config/raw",
            json={
                "expectedDraftRevision": "draft-1",
                "rawYaml": "sourceClusters: {}\n",
            },
        )

    assert response.status_code == 200
    assert drafts.expected_revision == "draft-1"
    assert drafts.raw_yaml == "sourceClusters: {}\n"


def test_config_save_discard_and_close_use_expected_revision(tmp_path):
    drafts = _Drafts()
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        config_drafts=drafts,
    )

    with TestClient(app) as client:
        saved = client.post(
            "/api/v1/config/save",
            json={"expectedDraftRevision": "draft-1"},
        )
        discarded = client.post(
            "/api/v1/config/discard",
            json={"expectedDraftRevision": "draft-1"},
        )
        closed = client.post(
            "/api/v1/config/close",
            json={"expectedDraftRevision": "draft-1"},
        )

    assert saved.status_code == 200
    assert discarded.status_code == 200
    assert closed.status_code == 204
    assert drafts.saved is True
    assert drafts.discarded is True
    assert drafts.closed is True


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
    drafts = _Drafts()
    operations = _Operations()
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        config_drafts=drafts,
        operations=operations,
        workflow_name="migration-test",
    )

    with TestClient(app) as client:
        review = client.post(
            "/api/v1/config/review",
            json={"expectedDraftRevision": "draft-1"},
        )
        response = client.post(
            "/api/v1/config/submit",
            json={"expectedDraftRevision": "draft-1"},
        )

    assert review.status_code == 200
    assert review.json()["valid"] is True
    assert review.json()["changes"][0]["resourceLabel"] == "capture"
    assert response.status_code == 202
    assert response.json()["id"] == "operation-submit"
    assert drafts.prepared is True
    assert drafts.submitted is False
    assert drafts.expected_revision == "draft-1"
    assert operations.started["kind"] == "submit"
    result = operations.started["worker"]()
    assert result.waiting is True
    assert result.result["workflowName"] == "migration-test"
    assert drafts.submitted is True


def test_config_preflight_reports_blocking_and_nonblocking_admission_results(
    tmp_path,
):
    drafts = _Drafts()
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        config_drafts=drafts,
        workflow_name="migration-test",
    )

    with TestClient(app) as client:
        response = client.post(
            "/api/v1/config/preflight",
            json={"expectedDraftRevision": "draft-1"},
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
    drafts = _Drafts()

    def fail_preflight(expected_revision, workflow_name):
        raise RuntimeError(
            "Workflow submission preparation failed with exit code 1\n"
            "Error: getaddrinfo ENOTFOUND localstack"
        )

    drafts.preflight = fail_preflight
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        config_drafts=drafts,
        workflow_name="migration-test",
    )

    with TestClient(app) as client:
        response = client.post(
            "/api/v1/config/preflight",
            json={"expectedDraftRevision": "draft-1"},
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


def test_config_removal_impact_returns_exact_dependent_paths(tmp_path):
    drafts = _Drafts()
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        config_drafts=drafts,
    )

    with TestClient(app) as client:
        response = client.post(
            "/api/v1/config/removal-impact",
            json={
                "expectedDraftRevision": "draft-1",
                "path": ["sourceClusters", "source"],
            },
        )

    assert response.status_code == 200
    assert response.json() == {
        "targetPath": ["sourceClusters", "source"],
        "targetLabel": "source",
        "affected": [{
            "path": ["traffic", "proxies", "capture"],
            "fieldPath": ["traffic", "proxies", "capture", "source"],
            "reason": "source=source",
        }],
    }
    assert drafts.expected_revision == "draft-1"


def test_config_revision_conflict_returns_current_recoverable_draft(tmp_path):
    drafts = _Drafts()

    def conflict(_expected_revision, _operation):
        raise ConfigDraftConflict(drafts.current)

    drafts.apply = conflict
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        config_drafts=drafts,
    )

    with TestClient(app) as client:
        response = client.post(
            "/api/v1/config/operations",
            json={
                "expectedDraftRevision": "stale",
                "operation": {
                    "op": "set",
                    "path": ["traffic", "enabled"],
                    "value": False,
                },
            },
        )

    assert response.status_code == 409
    assert response.json()["detail"]["code"] == "draft_revision_conflict"
    assert response.json()["detail"]["current"]["draftRevision"] == "draft-1"


def test_external_routes_return_keys_and_submit_exact_selection(tmp_path):
    drafts = _Drafts()
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        config_drafts=drafts,
    )

    with TestClient(app) as client:
        inventory = client.get(
            "/api/v1/external-resources",
            params={
                "nodeId": "edit:traffic.transform.configMap",
                "expectedDraftRevision": "draft-1",
            },
        )
        selected = client.post(
            "/api/v1/external-resources/select",
            json={
                "expectedDraftRevision": "draft-1",
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
    assert drafts.selection == {
        "expected_revision": "draft-1",
        "node_id": "edit:traffic.transform.configMap",
        "name": "transform",
        "kind": "ConfigMap",
        "group": "",
        "key": "main.js",
        "accept_warning": False,
        "manual": True,
    }


def test_external_detail_and_save_routes_never_return_secret_values(tmp_path):
    drafts = _Drafts()
    app = create_app(
        static_dir=_static_bundle(tmp_path),
        config_drafts=drafts,
    )

    with TestClient(app) as client:
        details = client.get(
            "/api/v1/external-resources/details",
            params={
                "nodeId": "edit:source.auth.secretName",
                "expectedDraftRevision": "draft-1",
                "name": "source-creds",
            },
        )
        saved = client.post(
            "/api/v1/external-resources/save",
            json={
                "expectedDraftRevision": "draft-1",
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
    assert drafts.external_read == {
        "expected_revision": "draft-1",
        "node_id": "edit:source.auth.secretName",
        "name": "source-creds",
    }

    assert saved.status_code == 200
    assert saved.json()["name"] == "next-creds"
    assert saved.json()["message"] == "Secret updated: next-creds"
    assert saved.json()["draft"]["draftRevision"] == "draft-1"
    assert drafts.external_save == {
        "expected_revision": "draft-1",
        "node_id": "edit:source.auth.secretName",
        "values": {
            "secretName": "next-creds",
            "username": "root",
            "password": "",
        },
        "confirmations": {"password": ""},
        "existing_name": "source-creds",
    }


def test_config_routes_without_a_draft_service_are_unavailable(tmp_path):
    app = create_app(static_dir=_static_bundle(tmp_path))

    with TestClient(app) as client:
        response = client.get("/api/v1/config")

    assert response.status_code == 503
    assert response.json()["detail"] == "Configuration editing is not configured"

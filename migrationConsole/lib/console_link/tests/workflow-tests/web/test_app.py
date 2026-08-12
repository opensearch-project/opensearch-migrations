import json
from datetime import datetime, timezone
from pathlib import Path

from fastapi.testclient import TestClient

from console_link.workflow.application.models import ManageNode, ManageSnapshot
from console_link.workflow.application.observations import (
    Observation,
    ObservationEvent,
)
from console_link.workflow.web.app import create_app


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


def test_checked_in_openapi_document_matches_the_application():
    web_dir = Path(__file__).parents[5] / "web"
    checked_in = json.loads((web_dir / "openapi.json").read_text(encoding="utf-8"))

    assert checked_in == create_app().openapi()


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

    async def get_observation(self, timeout=None):
        if isinstance(self.observation, Exception):
            raise self.observation
        return self.observation

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

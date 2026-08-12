import json
from pathlib import Path

from fastapi.testclient import TestClient

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

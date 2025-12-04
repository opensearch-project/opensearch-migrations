import os
import pytest
from fastapi.testclient import TestClient
from unittest.mock import patch

from console_link.api.main import app


@pytest.fixture
def fake_home(tmp_path):
    """Temporarily override Path.home() to point to a temp dir"""
    with patch("pathlib.Path.home", return_value=tmp_path):
        yield tmp_path


def test_healthcheck_ok():
    client = TestClient(app)
    response = client.get("/system/health")

    passed_check = {
        "status_code": 200,
        "data_status": "ok",
    }
    failed_check = {
        "status_code": 503
    }

    # This API depends on the host configuration matching, allowing for getting a 503 on dev machines
    expected_result = failed_check
    if os.path.exists("/shared-logs-output"):
        expected_result = passed_check

    assert response.status_code == expected_result["status_code"]
    data = response.json()
    if "data_status" in expected_result:
        assert data["status"] == expected_result["data_status"]


def test_version_ok(fake_home):
    version_path = fake_home / "VERSION"
    version_path.write_text("1.2.3")

    client = TestClient(app)
    response = client.get("/system/version")

    expected_result = {
        "status_code": 200,
        "version": "Migration Assistant 1.2.3",
    }

    assert response.status_code == expected_result["status_code"]
    data = response.json()
    assert data["version"] == expected_result["version"]

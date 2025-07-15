import os
from fastapi.testclient import TestClient
from console_link.api.main import app


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

import io
import json
from unittest.mock import Mock

from console_link.workflow.services.argo_observation_service import (
    load_slim_workflow,
)


def test_slim_workflow_preserves_retry_group_context():
    workflow = {
        "status": {
            "nodes": {
                "retry-group": {
                    "displayName": "reconcileCapturedTrafficResource",
                    "type": "Steps",
                    "phase": "Running",
                    "inputs": {
                        "parameters": [
                            {
                                "name": "retryGroupName_view",
                                "value": "CapturedTraffic: p2-topic",
                            },
                            {
                                "name": "irrelevant",
                                "value": "discard me",
                            },
                        ],
                    },
                },
            },
        },
    }
    response = Mock(
        status_code=200,
        raw=io.BytesIO(json.dumps(workflow).encode()),
    )
    service = Mock()
    service.get_workflow_status.return_value = {
        "success": True,
        "workflow": {"metadata": {"resourceVersion": "12"}},
        "phase": "Running",
        "started_at": "2026-08-15T10:00:00Z",
        "finished_at": None,
    }

    _, result = load_slim_workflow(
        service,
        "migration-workflow",
        "ma",
        argo_url="https://argo",
        token=None,
        insecure=True,
        request_get=lambda *_args, **_kwargs: response,
    )

    assert result["status"]["nodes"]["retry-group"]["inputs"] == {
        "parameters": [{
            "name": "retryGroupName_view",
            "value": "CapturedTraffic: p2-topic",
        }],
    }
    response.close.assert_called_once()
